package dev.madlador.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes the frame pointer from functions that do not need one.
 *
 * <p>The frame pointer costs four instructions in every function that keeps one —
 * {@code PUSH D}, {@code MOV D, SP} to establish it, {@code MOV SP, D} and
 * {@code POP D} to take it down. For a small function that is most of the code:
 *
 * <pre>
 *   word add(word a, word b) { return a + b; }
 *
 *   with a frame:  PUSH D; MOV D,SP; MOV C,[D+5]; MOV B,[D+7];
 *                  MOV A,C; ADD A,B; MOV SP,D; POP D; RET      = 9
 *   without:       MOV C,[SP+3]; MOV B,[SP+5]; MOV A,C;
 *                  ADD A,B; RET                                 = 5
 * </pre>
 *
 * <p>What the frame pointer actually buys is a base that does not move, so that one
 * displacement names a variable however far the body has since pushed the stack down.
 * Nothing else. So a function can do without it whenever the compiler can work that
 * displacement out itself — which it can, because it emitted every instruction that
 * moves the stack pointer and knows exactly how far each one moves it.
 *
 * <p>This pass therefore walks the body tracking the <em>stack depth</em>: how far
 * {@code SP} sits below where it was at entry. Pushing an argument deepens it by two,
 * the {@code ADD SP} that reclaims arguments after a call undoes that, reserving
 * locals deepens it by the frame size, and a call is itself neutral because the callee
 * balances its own stack. Branches make this a small dataflow rather than a straight
 * walk: a label reached along two paths must have the same depth on both, which is a
 * property structured code has and a check here confirms rather than assumes.
 *
 * <p>With the depth known at each instruction, every frame reference can be rewritten
 * against {@code SP}. The layout is unchanged except that the saved {@code D} is no
 * longer in it, so the frame base sits where {@code SP} was on entry — two bytes above
 * the base {@code MOV D, SP} would have captured, since that ran after {@code PUSH D}:
 *
 * <pre>
 *   arguments   [D+k]  -&gt;  [SP + k - 2 - depth]
 *   locals      [D-k]  -&gt;  [SP - k - depth]
 * </pre>
 *
 * <p>Locals keep their own displacements because they are measured downwards from that
 * base, which moved up by exactly the two bytes the saved {@code D} used to occupy. The
 * reservation is unchanged: {@code SUB SP, size} still lands the last slot at
 * {@code [SP+1]}, one byte above the stack pointer, which is the lowest address an
 * interrupt cannot tread on.
 *
 * <p>The teardown loses its way home: {@code MOV SP, D} restores a stack pointer this
 * function no longer records. It becomes {@code ADD SP, depth} instead — the same one
 * instruction, and correct by the same analysis that rewrote everything else.
 *
 * <p>Taking a local's address looked like the case with no answer, since
 * {@code &local} wants a base that does not move and {@code SP} is not one. It needs
 * no such thing. The selector emits the address as {@code MOV A, D} and then an
 * {@code ADD} or {@code SUB} of the local's offset, and the depth is as well known
 * there as anywhere, so the pair becomes {@code MOV A, SP} and one adjustment by the
 * {@code SP}-relative displacement — the same two instructions, and one when the
 * displacement is zero. What the address names is fixed once computed; that {@code SP}
 * moves afterwards does not matter.
 *
 * <p>Two things still defeat it, and each makes the pass hand the function back
 * unchanged rather than guess:
 *
 * <ul>
 *   <li><b>{@code D} used any other way,</b> which the selector does not emit, but
 *       which the pass refuses to reason about rather than assume.
 *   <li><b>A displacement that no longer fits.</b> The field is a signed byte, and the
 *       depth is added to every offset.
 * </ul>
 *
 * <p>The one indirect jump the compiler emits — a {@code switch} lowered to a jump
 * table — would be a third, since the table lives at module scope and the branch names
 * no label. It is handled by being told every label any table in the module can reach
 * and treating the jump as branching to all of them. That is an over-approximation
 * across several tables, and it costs nothing: it only means the depths of a few extra
 * labels have to agree, which in structured code they do.
 */
public final class FramePointerElimination {

    /** {@code CALL} pushed the return address, so entry {@code SP} is two below the base. */
    private static final int RETURN_ADDRESS_BYTES = 2;

    /** No path reaches this instruction. */
    private static final int UNREACHED = Integer.MIN_VALUE;

    /** The depth after this instruction cannot be determined. */
    private static final int UNKNOWN = Integer.MIN_VALUE;

    private FramePointerElimination() {
    }

    /**
     * @param jumpTableTargets every label reachable through a jump table in this
     *                         module, which is where an indirect jump can land
     */
    public static List<Asm.Line> apply(List<Asm.Line> body, Set<String> jumpTableTargets) {
        return apply(body, jumpTableTargets, Set.of());
    }

    /**
     * @param neverReturns labels outside this body that control does not come back
     *                     from, so that a branch to one ends its path rather than
     *                     defeating the analysis
     */
    public static List<Asm.Line> apply(List<Asm.Line> body, Set<String> jumpTableTargets,
                                       Set<String> neverReturns) {
        if (usesFramePointerOtherwise(body)) return body;
        int[] depth = stackDepths(body, jumpTableTargets, neverReturns);
        if (depth == null) return body;
        if (!displacementsFit(body, depth)) return body;
        if (!frameAddressesUnderstood(body, depth)) return body;
        return rewrite(body, depth);
    }

    /* ---------------- eligibility ---------------- */

    /**
     * Whether anything wants {@code D} itself, other than as the start of a frame
     * address, which {@link #rewrite} knows how to re-base.
     */
    private static boolean usesFramePointerOtherwise(List<Asm.Line> body) {
        for (Asm.Line line : body) {
            if (!(line instanceof Asm.Insn insn)) continue;
            if (isFramePointerBookkeeping(line)) continue;
            if (isFrameAddress(insn)) continue;
            for (Asm.Operand operand : insn.operands()) {
                if (operand instanceof Asm.Register register && isFramePointer(register.reg())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code MOV R, D}: the first half of {@code &local}, into a whole register. */
    private static boolean isFrameAddress(Asm.Insn insn) {
        return insn.mnemonic().equals("MOV") && insn.operands().size() == 2
                && insn.operands().get(0) instanceof Asm.Register target
                && ADDRESS_REGISTERS.contains(target.reg())
                && isRegister(insn.operands().get(1), Asm.Reg.D);
    }

    private static final Set<Asm.Reg> ADDRESS_REGISTERS =
            Set.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C);

    /**
     * How far the instruction after a frame address moves it from {@code D}: its
     * {@code ADD} or {@code SUB}, or the {@code INC} or {@code DEC} the peephole made of
     * one. Null when the next instruction is none of those.
     */
    private static Integer adjustmentAfter(List<Asm.Line> body, int at, Asm.Reg target) {
        if (at + 1 >= body.size() || !(body.get(at + 1) instanceof Asm.Insn next)) return null;
        List<Asm.Operand> operands = next.operands();
        if (operands.isEmpty() || !isRegister(operands.get(0), target)) return null;
        return switch (next.mnemonic()) {
            case "ADD" -> operands.size() == 2 && operands.get(1) instanceof Asm.Imm k ? k.value() : null;
            case "SUB" -> operands.size() == 2 && operands.get(1) instanceof Asm.Imm k ? -k.value() : null;
            case "INC" -> operands.size() == 1 ? 1 : null;
            case "DEC" -> operands.size() == 1 ? -1 : null;
            default -> null;
        };
    }

    /**
     * Every frame address sits where the stack depth is known, and is followed by its
     * adjustment. {@code D} alone would name the saved frame pointer, which no longer
     * exists once the frame pointer is gone; the selector never asks for it, and the
     * pass declines rather than guess.
     */
    private static boolean frameAddressesUnderstood(List<Asm.Line> body, int[] depth) {
        for (int at = 0; at < body.size(); at++) {
            if (!(body.get(at) instanceof Asm.Insn insn) || !isFrameAddress(insn)) continue;
            if (depth[at] == UNREACHED) return false;
            Asm.Reg target = ((Asm.Register) insn.operands().get(0)).reg();
            if (adjustmentAfter(body, at, target) == null) return false;
        }
        return true;
    }

    private static boolean isFramePointer(Asm.Reg register) {
        return register == Asm.Reg.D || register == Asm.Reg.DH || register == Asm.Reg.DL;
    }

    /* ---------------- stack depth ---------------- */

    /**
     * How far {@code SP} sits below its entry value at each instruction, or {@code null}
     * if that cannot be established everywhere.
     *
     * <p>Depths describe the body <em>after</em> the frame instructions are removed, so
     * {@code PUSH D} contributes nothing and {@code MOV SP, D} returns the depth to zero.
     */
    private static int[] stackDepths(List<Asm.Line> body, Set<String> jumpTableTargets,
                                     Set<String> neverReturns) {
        Map<String, Integer> labels = labelPositions(body);
        int[] depth = new int[body.size()];
        Arrays.fill(depth, UNREACHED);

        Deque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[] {0, 0});

        while (!pending.isEmpty()) {
            int[] entry = pending.pop();
            int at = entry[0];
            int current = entry[1];

            while (at < body.size()) {
                if (depth[at] != UNREACHED) {
                    // A second path here has to agree, or no one displacement works.
                    if (depth[at] != current) return null;
                    break;
                }
                // Popping past the entry point would need a negative reservation, and
                // the assembler has no negative immediate to undo it with.
                if (current > 0) return null;
                depth[at] = current;

                Asm.Line line = body.get(at);
                if (line instanceof Asm.Raw) return null;   // opaque text; assume the worst
                if (!(line instanceof Asm.Insn insn)) {
                    at++;
                    continue;
                }

                String mnemonic = insn.mnemonic();
                if (isFramePointerBookkeeping(line)) {
                    // MOV SP, D is the one that matters: it is about to become the
                    // instruction that returns the depth to zero.
                    if (mnemonic.equals("MOV") && isRegister(insn.operands().get(0), Asm.Reg.SP)) {
                        current = 0;
                    }
                    at++;
                    continue;
                }
                if (mnemonic.equals("RET") || mnemonic.equals("IRET") || mnemonic.equals("HLT")) {
                    break;
                }
                if (mnemonic.startsWith("J")) {
                    if (insn.operands().size() != 1) return null;
                    if (!(insn.operands().get(0) instanceof Asm.LabelRef target)) {
                        // JMP [A] through a jump table. The table is module-scope data,
                        // so the branch names no label; every label a table can reach
                        // stands in for the one it will actually pick.
                        if (!mnemonic.equals("JMP")) return null;
                        int found = 0;
                        for (String reachable : jumpTableTargets) {
                            Integer arm = labels.get(reachable);
                            if (arm != null) {
                                pending.push(new int[] {arm, current});
                                found++;
                            }
                        }
                        // No target in this body means the jump goes somewhere the walk
                        // cannot follow, and an unwalked path is an unchecked depth.
                        if (found == 0) return null;
                        break;
                    }
                    Integer destination = labels.get(target.label());
                    if (destination == null) {
                        // A branch out of the body. Sound to ignore only when the
                        // target cannot come back — the stack-overflow reporter ends
                        // in HLT — so that path simply stops, like a RET.
                        if (neverReturns.contains(target.label())) {
                            if (mnemonic.equals("JMP")) break;
                            at++;
                            continue;
                        }
                        return null;
                    }
                    pending.push(new int[] {destination, current});
                    if (mnemonic.equals("JMP")) break;
                    at++;
                    continue;
                }

                int effect = stackEffect(insn);
                if (effect == UNKNOWN) return null;
                current += effect;
                at++;
            }
        }
        return depth;
    }

    private static Map<String, Integer> labelPositions(List<Asm.Line> body) {
        Map<String, Integer> positions = new HashMap<>();
        for (int at = 0; at < body.size(); at++) {
            if (body.get(at) instanceof Asm.Label label) positions.put(label.name(), at);
        }
        return positions;
    }

    /** How much an instruction moves {@code SP}, or {@link #UNKNOWN}. */
    private static int stackEffect(Asm.Insn insn) {
        String mnemonic = insn.mnemonic();

        // A callee balances its own stack, so by the time control comes back SP is
        // where it was. Reclaiming the arguments is a separate ADD, counted below.
        if (mnemonic.equals("CALL")) return 0;
        if (mnemonic.equals("PUSH")) return -2;
        if (mnemonic.equals("POP")) return 2;
        // The compiler never emits the byte forms, and whether they move SP by one or
        // by two is not worth guessing at.
        if (mnemonic.equals("PUSHB") || mnemonic.equals("POPB")) return UNKNOWN;

        if (insn.operands().isEmpty()) return 0;
        if (!isRegister(insn.operands().get(0), Asm.Reg.SP)) return 0;

        Asm.Operand amount = insn.operands().size() == 2 ? insn.operands().get(1) : null;
        return switch (mnemonic) {
            case "CMP", "CMPB" -> 0;    // reads SP without moving it
            case "INC" -> 1;
            case "DEC" -> -1;
            case "ADD" -> amount instanceof Asm.Imm imm ? imm.value() : UNKNOWN;
            case "SUB" -> amount instanceof Asm.Imm imm ? -imm.value() : UNKNOWN;
            default -> UNKNOWN;
        };
    }

    /* ---------------- the rewrite ---------------- */

    /**
     * Whether every rewritten displacement still fits the signed byte the machine
     * encodes it in — and whether any frame reference sits somewhere no depth was
     * established, which would mean the walk above missed an edge.
     */
    private static boolean displacementsFit(List<Asm.Line> body, int[] depth) {
        for (int at = 0; at < body.size(); at++) {
            if (!(body.get(at) instanceof Asm.Insn insn)) continue;
            for (Asm.Operand operand : insn.operands()) {
                if (!(operand instanceof Asm.Indirect indirect)) continue;
                if (indirect.base() != Asm.Reg.D) continue;
                if (depth[at] == UNREACHED) return false;
                int rewritten = stackDisplacement(indirect.offset(), depth[at]);
                if (rewritten < FrameLayout.MIN_OFFSET || rewritten > FrameLayout.MAX_OFFSET) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Where a frame displacement lands relative to {@code SP}.
     *
     * <p>Arguments are measured from the frame base upwards, and that base is two bytes
     * above entry {@code SP} because {@code CALL} pushed the return address in between.
     * Locals are measured downwards from the base this pass keeps, which is entry
     * {@code SP} itself. Both then shift by however far the stack has grown since.
     */
    private static int stackDisplacement(int frameOffset, int depth) {
        int fromEntry = frameOffset > 0 ? frameOffset - RETURN_ADDRESS_BYTES : frameOffset;
        return fromEntry - depth;
    }

    private static List<Asm.Line> rewrite(List<Asm.Line> body, int[] depth) {
        List<Asm.Line> out = new ArrayList<>(body.size());
        for (int at = 0; at < body.size(); at++) {
            Asm.Line line = body.get(at);

            if (isFramePointerBookkeeping(line)) {
                Asm.Insn insn = (Asm.Insn) line;
                boolean restoresStackPointer = insn.mnemonic().equals("MOV")
                        && isRegister(insn.operands().get(0), Asm.Reg.SP);
                if (restoresStackPointer && depth[at] != UNREACHED && depth[at] != 0) {
                    out.add(new Asm.Insn("ADD",
                            List.of(new Asm.Register(Asm.Reg.SP), new Asm.Imm(-depth[at])),
                            "drop locals"));
                }
                continue;
            }
            if (line instanceof Asm.Insn insn && isFrameAddress(insn)) {
                Asm.Reg target = ((Asm.Register) insn.operands().get(0)).reg();
                int adjustment = adjustmentAfter(body, at, target);
                int displacement = stackDisplacement(adjustment, depth[at]);
                out.add(new Asm.Insn("MOV",
                        List.of(new Asm.Register(target), new Asm.Register(Asm.Reg.SP)),
                        insn.comment()));
                // The assembler takes no negative immediate, so a displacement below
                // SP is a SUB.
                if (displacement > 0) {
                    out.add(new Asm.Insn("ADD",
                            List.of(new Asm.Register(target), new Asm.Imm(displacement)), null));
                } else if (displacement < 0) {
                    out.add(new Asm.Insn("SUB",
                            List.of(new Asm.Register(target), new Asm.Imm(-displacement)), null));
                }
                at++;   // the adjustment is folded into the displacement
                continue;
            }
            out.add(rewriteFrameReferences(line, depth[at]));
        }
        return out;
    }

    /** Turns every {@code [D±k]} in one instruction into its {@code SP}-relative form. */
    private static Asm.Line rewriteFrameReferences(Asm.Line line, int depth) {
        if (!(line instanceof Asm.Insn insn)) return line;

        List<Asm.Operand> rewritten = new ArrayList<>(insn.operands().size());
        boolean changed = false;
        for (Asm.Operand operand : insn.operands()) {
            if (operand instanceof Asm.Indirect indirect && indirect.base() == Asm.Reg.D) {
                rewritten.add(new Asm.Indirect(Asm.Reg.SP,
                        stackDisplacement(indirect.offset(), depth)));
                changed = true;
            } else {
                rewritten.add(operand);
            }
        }
        return changed ? new Asm.Insn(insn.mnemonic(), rewritten, insn.comment()) : insn;
    }

    /* ---------------- recognising the frame instructions ---------------- */

    /** {@code PUSH D}, {@code MOV D, SP}, {@code MOV SP, D}, {@code POP D}. */
    private static boolean isFramePointerBookkeeping(Asm.Line line) {
        if (!(line instanceof Asm.Insn insn)) return false;
        List<Asm.Operand> operands = insn.operands();

        if ((insn.mnemonic().equals("PUSH") || insn.mnemonic().equals("POP"))
                && operands.size() == 1 && isRegister(operands.get(0), Asm.Reg.D)) {
            return true;
        }
        if (insn.mnemonic().equals("MOV") && operands.size() == 2) {
            boolean establish = isRegister(operands.get(0), Asm.Reg.D)
                    && isRegister(operands.get(1), Asm.Reg.SP);
            boolean tearDown = isRegister(operands.get(0), Asm.Reg.SP)
                    && isRegister(operands.get(1), Asm.Reg.D);
            return establish || tearDown;
        }
        return false;
    }

    private static boolean isRegister(Asm.Operand operand, Asm.Reg register) {
        return operand instanceof Asm.Register r && r.reg() == register;
    }
}
