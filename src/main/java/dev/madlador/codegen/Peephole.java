package dev.madlador.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.Set;

/**
 * Local cleanups on the emitted assembly.
 *
 * <p>Everything here is a pattern that is easier to recognise once real registers
 * and offsets exist than it was in the IR. The rules are deliberately conservative:
 * a label may be entered from anywhere, so all knowledge resets there, and nothing
 * moves across a branch.
 */
public final class Peephole {

    private Peephole() {
    }

    public static List<Asm.Line> optimize(List<Asm.Line> lines, Set<Integer> temporarySlots) {
        List<Asm.Line> current = lines;
        // The rules feed each other: dropping a reload can expose a dead store.
        for (int round = 0; round < 4; round++) {
            List<Asm.Line> next = removeRedundantJumps(current);
            next = invertBranchesOverJumps(next);
            next = removeRedundantLoads(next);
            next = removeDeadStores(next, temporarySlots);
            next = useIncrementAndDecrement(next);
            if (next.size() == current.size()) return next;
            current = next;
        }
        return current;
    }

    /* ---------------- jumps ---------------- */

    /**
     * Turns a branch over a jump into a branch with the opposite condition.
     *
     * <pre>
     *       JZ    .taken          becomes       JNZ   .skipped
     *       JMP   .skipped
     *   .taken:                                 .taken:
     * </pre>
     *
     * <p>Lowering produces this shape everywhere, because a conditional in the IR
     * carries both of its successors and the selector emits the true edge as a branch
     * and the false edge as a jump. Whenever the true edge happens to be the next
     * block, the jump is the only instruction that actually goes anywhere.
     *
     * <p>Worth more than it looks. Every {@code if}, every loop test and every
     * short-circuit costs one, so it is one instruction off the price of a branch —
     * and inside a loop that is one instruction per iteration.
     */
    private static List<Asm.Line> invertBranchesOverJumps(List<Asm.Line> lines) {
        List<Asm.Line> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String inverse = i + 1 < lines.size() ? invertedBranchAt(lines, i) : null;
            if (inverse != null) {
                Asm.Insn jump = (Asm.Insn) lines.get(i + 1);
                out.add(new Asm.Insn(inverse, jump.operands(), jump.comment()));
                i++;    // the JMP has been folded into the branch
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    /** The opposite mnemonic, if lines {@code i} and {@code i + 1} are that shape. */
    private static String invertedBranchAt(List<Asm.Line> lines, int i) {
        if (!(lines.get(i) instanceof Asm.Insn branch)) return null;
        if (!(lines.get(i + 1) instanceof Asm.Insn jump)) return null;
        if (!jump.mnemonic().equals("JMP")) return null;
        if (branch.operands().size() != 1 || jump.operands().size() != 1) return null;
        if (!(branch.operands().get(0) instanceof Asm.LabelRef taken)) return null;
        if (!(jump.operands().get(0) instanceof Asm.LabelRef)) return null;

        String inverse = inverseOf(branch.mnemonic());
        if (inverse == null) return null;
        // Only when falling through lands exactly where the branch was going.
        return nextLabelIs(lines, i + 2, taken.label()) ? inverse : null;
    }

    /**
     * The opposite condition, or null where there is not exactly one.
     *
     * <p>The machine has no sign flag, so these are the three pairs there are: zero,
     * carry, and unsigned above. Anything else must be left alone — inverting a
     * condition that has no true complement would change what the program does.
     */
    private static String inverseOf(String mnemonic) {
        return switch (mnemonic) {
            case "JZ" -> "JNZ";
            case "JNZ" -> "JZ";
            case "JC" -> "JNC";
            case "JNC" -> "JC";
            case "JA" -> "JNA";
            case "JNA" -> "JA";
            default -> null;
        };
    }

    /** {@code JMP L} immediately before {@code L:} does nothing. */
    private static List<Asm.Line> removeRedundantJumps(List<Asm.Line> lines) {
        List<Asm.Line> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i) instanceof Asm.Insn insn
                    && insn.mnemonic().equals("JMP")
                    && insn.operands().size() == 1
                    && insn.operands().get(0) instanceof Asm.LabelRef ref
                    && nextLabelIs(lines, i + 1, ref.label())) {
                continue;
            }
            out.add(lines.get(i));
        }
        return out;
    }

    private static boolean nextLabelIs(List<Asm.Line> lines, int from, String label) {
        for (int i = from; i < lines.size(); i++) {
            switch (lines.get(i)) {
                case Asm.Label l -> {
                    return l.name().equals(label);
                }
                case Asm.Comment ignored -> { }
                case Asm.Blank ignored -> { }
                default -> {
                    return false;
                }
            }
        }
        return false;
    }

    /* ---------------- redundant loads ---------------- */

    /** The registers whose contents are tracked: the scratch and the two colours. */
    private static final List<Asm.Reg> TRACKED = List.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C);

    /**
     * Drops a load into a register of something that register demonstrably already
     * holds.
     *
     * <p>The common case is a value stored to its slot or its global and read straight
     * back, which the selector emits whenever a computed value is consumed at once.
     * This used to track {@code A} alone, so a global stored from {@code B} and read
     * back into {@code B} reloaded it; now each of A, B and C carries its own fact:
     * "this location currently holds the same bits as the register".
     *
     * <p>A fact has to be discarded the moment anything could make it untrue: a write to
     * the register — a byte half included — a write to any register the location is
     * addressed through, a write to {@code D} or {@code SP} for anything frame-relative,
     * and any store to memory at all for a memory location, since words overlap and a
     * store through a pointer could land anywhere. A load is only remembered when its
     * address does not use the register it loads: after {@code MOV A, [A]}, {@code [A]}
     * names a different word, and remembering it deleted nothing yet only by luck.
     */
    private static List<Asm.Line> removeRedundantLoads(List<Asm.Line> lines) {
        List<Asm.Line> out = new ArrayList<>(lines.size());
        Map<Asm.Reg, String> held = new EnumMap<>(Asm.Reg.class);

        for (Asm.Line line : lines) {
            if (!(line instanceof Asm.Insn insn)) {
                if (line instanceof Asm.Label) held.clear();
                out.add(line);
                continue;
            }
            String mnemonic = insn.mnemonic();
            List<Asm.Operand> operands = insn.operands();

            // MOV R, <source>
            if (mnemonic.equals("MOV") && operands.size() == 2
                    && operands.get(0) instanceof Asm.Register target
                    && TRACKED.contains(target.reg())) {
                String source = Asm.render(operands.get(1));
                String had = held.get(target.reg());
                if (source.equals(had)) continue;   // already there
                Asm.Reg copied = operands.get(1) instanceof Asm.Register r
                        && TRACKED.contains(r.reg()) && r.reg() != target.reg() ? r.reg() : null;
                // Two registers mirroring the same location hold the same bits.
                if (copied != null && had != null && had.equals(held.get(copied))) continue;
                forgetRegister(held, target.reg());
                if (copied != null && held.containsKey(copied)) {
                    // The copy inherits what its source mirrors, so a later copy back
                    // is seen for what it is either way round.
                    held.put(target.reg(), held.get(copied));
                } else {
                    if (!mentions(source, target.reg())) held.put(target.reg(), source);
                    // A copy between registers runs both ways: after MOV B, A the A
                    // that follows is in B already.
                    if (copied != null) held.put(copied, Asm.render(target));
                }
                out.add(line);
                continue;
            }

            // MOV <destination>, R — the destination now holds what R does.
            if (mnemonic.equals("MOV") && operands.size() == 2
                    && operands.get(1) instanceof Asm.Register value
                    && TRACKED.contains(value.reg())
                    && !clobbersFrameBase(operands.get(0))) {
                Asm.Operand destination = operands.get(0);
                if (destination instanceof Asm.Register register) {
                    forgetRegister(held, wholeRegister(register.reg()));
                } else {
                    forgetMemory(held);
                }
                String name = Asm.render(destination);
                if (!mentions(name, value.reg())) held.put(value.reg(), name);
                out.add(line);
                continue;
            }

            forgetWhatChanges(held, insn);
            out.add(line);
        }
        return out;
    }

    /** Discards whatever {@code insn} could have made untrue. */
    private static void forgetWhatChanges(Map<Asm.Reg, String> held, Asm.Insn insn) {
        if (held.isEmpty()) return;
        String mnemonic = insn.mnemonic();

        // A branch or call means the following instruction may be reached another way,
        // and PUSH and POP both move SP and touch memory.
        if (mnemonic.equals("CALL") || mnemonic.equals("RET") || mnemonic.startsWith("J")
                || mnemonic.startsWith("PUSH") || mnemonic.startsWith("POP")) {
            held.clear();
            return;
        }
        // MUL, DIV and IN write A without naming it.
        if (mnemonic.equals("MUL") || mnemonic.equals("DIV") || mnemonic.equals("IN")) {
            forgetRegister(held, Asm.Reg.A);
        }
        if (insn.operands().isEmpty() || !writesFirstOperand(mnemonic)) return;

        Asm.Operand destination = insn.operands().get(0);
        if (destination instanceof Asm.Register register) {
            // The whole register: writing AL changes A, writing BL moves [B+2].
            forgetRegister(held, wholeRegister(register.reg()));
        } else {
            forgetMemory(held);
        }
    }

    /**
     * Forgets what {@code register} held, and every location named through it. For D
     * and SP that is every frame-relative location.
     */
    private static void forgetRegister(Map<Asm.Reg, String> held, Asm.Reg register) {
        held.remove(register);
        held.values().removeIf(location -> mentions(location, register));
        if (register == Asm.Reg.D || register == Asm.Reg.SP) {
            held.values().removeIf(location ->
                    mentions(location, Asm.Reg.D) || mentions(location, Asm.Reg.SP));
        }
    }

    /** Forgets every memory location: a store may overlap or alias any of them. */
    private static void forgetMemory(Map<Asm.Reg, String> held) {
        held.values().removeIf(location -> location.startsWith("["));
    }



    /**
     * The 16-bit register a name refers to, so that a write to a half is understood to
     * change the whole. {@code FoldLoads} and {@code DeadMoves} each carry a copy of
     * this; three is one too many, but they are deliberately independent passes and a
     * shared helper would couple them for four lines.
     */
    private static Asm.Reg wholeRegister(Asm.Reg register) {
        return switch (register) {
            case AH, AL -> Asm.Reg.A;
            case BH, BL -> Asm.Reg.B;
            case CH, CL -> Asm.Reg.C;
            case DH, DL -> Asm.Reg.D;
            default -> register;
        };
    }

    /** Whether a tracked operand names {@code register}, directly or as a base. */
    private static boolean mentions(String held, Asm.Reg register) {
        String name = register.name();
        if (held.equals(name)) return true;
        if (!held.startsWith("[" + name)) return false;
        // Guard against [B] matching a hypothetical [BL]: the next character must
        // end the operand or begin a displacement.
        char following = held.charAt(1 + name.length());
        return following == ']' || following == '+' || following == '-';
    }

    /** True for D and SP, and for the byte halves of D. */
    private static boolean clobbersFrameBase(Asm.Operand operand) {
        return operand instanceof Asm.Register r
                && (r.reg() == Asm.Reg.D || r.reg() == Asm.Reg.SP
                || r.reg() == Asm.Reg.DH || r.reg() == Asm.Reg.DL);
    }

    /** Whether an instruction assigns to its first operand. */
    private static boolean writesFirstOperand(String mnemonic) {
        return switch (mnemonic) {
            case "MOV", "MOVB", "ADD", "ADDB", "SUB", "SUBB", "AND", "ANDB",
                 "OR", "ORB", "XOR", "XORB", "SHL", "SHLB", "SHR", "SHRB",
                 "INC", "INCB", "DEC", "DECB", "NOT", "NOTB", "POP", "POPB" -> true;
            // MUL and DIV take a single SOURCE operand and write A implicitly.
            // CMP, PUSH and OUT only read.
            default -> false;
        };
    }

    /**
     * Whether an instruction reads its first operand.
     *
     * <p>Only a two-operand MOV overwrites without reading. Everything else either
     * reads it as a source — MUL and DIV most importantly, whose single operand
     * is the multiplier or divisor while A is the destination — or reads and writes
     * it, as the two-operand ALU instructions do.
     */
    private static boolean readsFirstOperand(String mnemonic, int operandCount) {
        if (operandCount == 0) return false;
        boolean plainMove = mnemonic.equals("MOV") || mnemonic.equals("MOVB");
        return !(plainMove && operandCount == 2);
    }

    /* ---------------- dead stores ---------------- */

    /**
     * Removes a store to a compiler temporary that nothing reads afterwards.
     *
     * <p>Restricted to temporaries — slots the compiler invented for intermediate
     * values — because a named variable might be reached through a pointer, which
     * this local view cannot see. For the same reason the whole rule stands down if
     * the function computes a frame address at all.
     */
    private static List<Asm.Line> removeDeadStores(List<Asm.Line> lines, Set<Integer> temporarySlots) {
        if (temporarySlots.isEmpty()) return lines;
        if (computesFrameAddress(lines)) return lines;

        Set<String> read = new HashSet<>();
        for (Asm.Line line : lines) {
            if (!(line instanceof Asm.Insn insn)) continue;
            List<Asm.Operand> operands = insn.operands();
            for (int i = 0; i < operands.size(); i++) {
                boolean isRead = (i > 0) || readsFirstOperand(insn.mnemonic(), operands.size());
                if (isRead) read.add(Asm.render(operands.get(i)));
            }
        }

        List<Asm.Line> out = new ArrayList<>(lines.size());
        for (Asm.Line line : lines) {
            if (line instanceof Asm.Insn insn
                    && insn.mnemonic().equals("MOV")
                    && insn.operands().size() == 2
                    && insn.operands().get(0) instanceof Asm.Indirect indirect
                    && indirect.base() == Asm.Reg.D
                    && temporarySlots.contains(indirect.offset())
                    && !read.contains(Asm.render(indirect))) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Whether anything takes the address of a frame slot, which defeats the above. */
    private static boolean computesFrameAddress(List<Asm.Line> lines) {
        for (Asm.Line line : lines) {
            if (line instanceof Asm.Insn insn
                    && insn.mnemonic().equals("MOV")
                    && insn.operands().size() == 2
                    && isRegister(insn.operands().get(0), Asm.Reg.A)
                    && isRegister(insn.operands().get(1), Asm.Reg.D)) {
                return true;
            }
        }
        return false;
    }

    /* ---------------- size ---------------- */

    /** {@code ADD r, 1} is {@code INC r}: the same one tick, two bytes smaller. */
    private static List<Asm.Line> useIncrementAndDecrement(List<Asm.Line> lines) {
        List<Asm.Line> out = new ArrayList<>(lines.size());
        for (Asm.Line line : lines) {
            if (line instanceof Asm.Insn insn
                    && insn.operands().size() == 2
                    && insn.operands().get(0) instanceof Asm.Register register
                    && insn.operands().get(1) instanceof Asm.Imm imm
                    && imm.value() == 1
                    && !register.reg().isByteWide()) {

                if (insn.mnemonic().equals("ADD")) {
                    out.add(new Asm.Insn("INC", List.of(insn.operands().get(0)), insn.comment()));
                    continue;
                }
                if (insn.mnemonic().equals("SUB")) {
                    out.add(new Asm.Insn("DEC", List.of(insn.operands().get(0)), insn.comment()));
                    continue;
                }
            }
            out.add(line);
        }
        return out;
    }

    private static boolean isRegister(Asm.Operand operand, Asm.Reg register) {
        return operand instanceof Asm.Register r && r.reg() == register;
    }
}
