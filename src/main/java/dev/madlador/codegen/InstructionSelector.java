package dev.madlador.codegen;

import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.Span;
import dev.madlador.ir.Ir;
import dev.madlador.regalloc.GraphColoring;
import dev.madlador.regalloc.Liveness;
import dev.madlador.sema.Mangler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns IR into assembly.
 *
 * <p>Register {@code A} is the accumulator for every computation, which is not an
 * arbitrary choice: {@code MUL} and {@code DIV} are single-operand instructions that
 * read and write {@code A} implicitly, so arranging results there is what the
 * machine already wants.
 *
 * <p>The other thing worth knowing is that the ALU takes a memory source operand:
 * {@code ADD A, [D-3]} and {@code DIV [D-5]} are legal and cost one clock tick,
 * exactly like the register forms. Operands living in frame slots are therefore read
 * straight from memory instead of being loaded first, which is why the naive output
 * here is already close to what a register allocator would produce for a two-operand
 * machine. Memory is never an ALU <em>destination</em>, though, so results always
 * come back through {@code A}.
 */
public final class InstructionSelector {

    private final DiagnosticReporter reporter;
    private final Mangler labels;
    private final List<Asm.Line> out = new ArrayList<>();

    private SlotAllocator slots;
    private GraphColoring.Allocation allocation;

    /** What the current instruction leaves live, or null when nothing was allocated. */
    private Liveness liveness;

    private Set<Ir.VReg> liveAfter;
    private final java.util.Set<Integer> temporaryOffsets = new java.util.LinkedHashSet<>();
    private FrameLayout frame;
    private String epilogueLabel;
    private boolean epilogueUsed;
    private boolean guardStack;

    public InstructionSelector(DiagnosticReporter reporter, Mangler labels) {
        this.reporter = reporter;
        this.labels = labels;
    }

    /** Frame displacements holding compiler temporaries, from the last selection. */
    public java.util.Set<Integer> temporaryOffsets() {
        return java.util.Set.copyOf(temporaryOffsets);
    }

    public List<Asm.Line> select(Ir.Function function, int namedSlots, String epilogueLabel) {
        return select(function, namedSlots, epilogueLabel, null);
    }

    public List<Asm.Line> select(Ir.Function function, int namedSlots, String epilogueLabel,
                                 GraphColoring.Allocation allocation) {
        return select(function, namedSlots, epilogueLabel, allocation, false);
    }

    /**
     * @param guardStack whether to check the stack on entry, which is worth its two
     *                   instructions only in a function that can recurse
     */
    public List<Asm.Line> select(Ir.Function function, int namedSlots, String epilogueLabel,
                                 GraphColoring.Allocation allocation, boolean guardStack) {
        return select(function, namedSlots, epilogueLabel, allocation, guardStack, null);
    }

    /**
     * @param liveness what each instruction leaves live, for picking a safe scratch
     *                 register; null when nothing was allocated and none is needed
     */
    public List<Asm.Line> select(Ir.Function function, int namedSlots, String epilogueLabel,
                                 GraphColoring.Allocation allocation, boolean guardStack,
                                 Liveness liveness) {
        this.guardStack = guardStack;
        this.allocation = allocation;
        this.liveness = liveness;
        this.slots = (allocation == null)
                ? new SlotAllocator(function, namedSlots)
                : new SlotAllocator(function, namedSlots, allocation::isSpilled);
        this.frame = new FrameLayout(slots.totalSlots());
        this.epilogueLabel = epilogueLabel;
        this.epilogueUsed = false;
        out.clear();
        temporaryOffsets.clear();
        // Offsets above the named variables belong to compiler temporaries, which a
        // dead-store peephole may remove; a named variable might be reached through
        // a pointer, so those are off limits.
        for (int slot = namedSlots; slot < slots.totalSlots(); slot++) {
            temporaryOffsets.add(FrameLayout.slotOffset(slot));
        }

        checkFrameFits(function);
        emitPrologue(function);

        for (Ir.BasicBlock block : function.blocks()) {
            // The entry block's label is the function label, already emitted.
            if (block != function.entry()) {
                out.add(new Asm.Label(block.label()));
            }
            List<Set<Ir.VReg>> liveAfterEach =
                    liveness == null ? null : liveness.liveAfterEach(block);
            List<Ir.Instr> instructions = block.instructions();
            for (int i = 0; i < instructions.size(); i++) {
                Ir.Instr instruction = instructions.get(i);
                liveAfter = liveAfterEach == null ? null : liveAfterEach.get(i);
                selectInstruction(instruction);
            }
        }

        emitEpilogue();
        return List.copyOf(out);
    }

    private void checkFrameFits(Ir.Function function) {
        if (slots.totalSlots() > FrameLayout.MAX_SLOTS) {
            // The assembler would otherwise fail with "offset must be a value between
            // -128...+127", which means nothing to someone writing Mona.
            reporter.error(Span.NONE,
                    "function '" + function.name() + "' needs " + slots.totalSlots()
                            + " frame slots, but at most " + FrameLayout.MAX_SLOTS
                            + " can be addressed",
                    "indirect operands have a signed-byte displacement");
            throw new CompileException("frame too large");
        }
        if (function.parameterCount() > FrameLayout.MAX_ARGS) {
            reporter.error(Span.NONE,
                    "function '" + function.name() + "' takes " + function.parameterCount()
                            + " arguments, but at most " + FrameLayout.MAX_ARGS + " can be addressed");
            throw new CompileException("too many parameters");
        }
    }

    /* ---------------- prologue and epilogue ---------------- */

    private void emitPrologue(Ir.Function function) {
        out.add(new Asm.Label(function.label()));
        out.add(new Asm.Insn("PUSH", List.of(reg(Asm.Reg.D)), "save caller frame pointer"));
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.D), reg(Asm.Reg.SP)), "D = frame base"));
        if (frame.frameSize() > 0) {
            out.add(new Asm.Insn("SUB", List.of(reg(Asm.Reg.SP), new Asm.Imm(frame.frameSize())),
                    frame.slotCount() + " slot(s) of locals"));
        }
        emitStackGuard();
    }

    /**
     * Checks that this frame has not crossed the stack's floor.
     *
     * <p>After the locals are reserved, so {@code SP} is as low as it will go for this
     * call and nothing has yet been written below it. Before the body, so the report
     * happens while there is still a program to run it.
     *
     * <p>Emitted only in functions that can reach themselves — everything else has a
     * depth the compiler added up at compile time, and pays nothing.
     */
    private void emitStackGuard() {
        if (!guardStack) return;
        out.add(new Asm.Insn("CMP", List.of(reg(Asm.Reg.SP),
                new Asm.Absolute(Runtime.STACK_LIMIT)), "the stack's floor"));
        out.add(new Asm.Insn("JC", List.of(new Asm.LabelRef(Runtime.STACK_OVERFLOW)),
                "below it: say so and stop"));
    }

    private void emitEpilogue() {
        if (epilogueUsed) {
            out.add(new Asm.Label(epilogueLabel));
        }
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.SP), reg(Asm.Reg.D)), "drop locals"));
        out.add(new Asm.Insn("POP", List.of(reg(Asm.Reg.D)), "restore caller frame pointer"));
        out.add(new Asm.Insn("RET"));
        out.add(new Asm.Blank());
    }

    /* ---------------- instruction selection ---------------- */

    private void selectInstruction(Ir.Instr instruction) {
        switch (instruction) {
            case Ir.Const c -> selectConst(c);
            case Ir.Copy c -> selectCopy(c);
            case Ir.Bin b -> selectBin(b);
            case Ir.Un u -> selectUn(u);
            case Ir.Load l -> selectLoad(l);
            case Ir.Store s -> selectStore(s);
            case Ir.Ret r -> selectRet(r);
            case Ir.Br b -> out.add(new Asm.Insn("JMP", new Asm.LabelRef(b.target())));
            case Ir.Cmp c -> selectCmp(c);
            case Ir.Cbr c -> selectCbr(c);
            case Ir.Call c -> selectCall(c);
            case Ir.AddrOf a -> selectAddrOf(a);
            case Ir.Flag f -> out.add(new Asm.Insn(switch (f.op()) {
                case ENABLE_INTERRUPTS -> "STI";
                case DISABLE_INTERRUPTS -> "CLI";
                case HALT -> "HLT";
            }, List.of(), null));
            case Ir.ArgIn a -> selectArgIn(a);
            case Ir.TableBr t -> selectTableBr(t);
            case Ir.In i -> selectIn(i);
            case Ir.Out o -> selectOut(o);
        }
    }

    private void selectConst(Ir.Const instruction) {
        // MOV takes an immediate into either a register or memory, so a constant
        // never has to pass through A.
        out.add(new Asm.Insn("MOV",
                List.of(slotOf(instruction.dst()), new Asm.Imm(instruction.value())), null));
    }

    private void selectCopy(Ir.Copy instruction) {
        Asm.Reg home = homeOf(instruction.dst());
        if (home != null) {
            moveInto(home, instruction.src());
            return;
        }
        if (instruction.src() instanceof Ir.Imm imm) {
            out.add(new Asm.Insn("MOV",
                    List.of(slotOf(instruction.dst()), new Asm.Imm(imm.value())), null));
            return;
        }
        // Memory is never an ALU destination and there is no memory-to-memory move,
        // so a spilled copy of a spilled value goes through A.
        if (instruction.src() instanceof Ir.VReg source && homeOf(source) != null) {
            out.add(new Asm.Insn("MOV",
                    List.of(slotOf(instruction.dst()), reg(homeOf(source))), null));
            return;
        }
        loadIntoA(instruction.src());
        storeAInto(instruction.dst());
    }

    private void selectBin(Ir.Bin instruction) {
        if (instruction.op() == Ir.BinOp.MOD) {
            selectMod(instruction);
            return;
        }

        instruction = accumulateInPlace(instruction);
        Asm.Operand rhs = operandOf(instruction.rhs());

        // MUL and DIV have no choice: they read and write A implicitly.
        if (instruction.op() == Ir.BinOp.MUL || instruction.op() == Ir.BinOp.DIV) {
            moveInto(Asm.Reg.A, instruction.lhs());
            out.add(new Asm.Insn(instruction.op() == Ir.BinOp.MUL ? "MUL" : "DIV",
                    List.of(rhs), null));
            writeResult(instruction.dst(), Asm.Reg.A);
            return;
        }

        Asm.Reg target = computeIn(instruction.dst(), instruction.lhs(), instruction.rhs());
        moveInto(target, instruction.lhs());

        String mnemonic = switch (instruction.op()) {
            case ADD -> "ADD";
            case SUB -> "SUB";
            case AND -> "AND";
            case OR -> "OR";
            case XOR -> "XOR";
            case SHL -> "SHL";
            case SHR -> "SHR";
            default -> throw new IllegalStateException("handled above: " + instruction.op());
        };
        out.add(new Asm.Insn(mnemonic, List.of(reg(target), rhs), null));
        writeResult(instruction.dst(), target);
    }

    /**
     * There is no MOD instruction, so {@code a % b} becomes
     * {@code a - (a / b) * b}. Both operands are read twice, which is free because
     * they live in frame slots and DIV/MUL accept a memory operand.
     */
    private void selectMod(Ir.Bin instruction) {
        Asm.Operand rhs = operandOf(instruction.rhs());

        moveInto(Asm.Reg.A, instruction.lhs());
        out.add(new Asm.Insn("DIV", List.of(rhs), "a / b"));
        out.add(new Asm.Insn("MUL", List.of(rhs), "(a / b) * b"));

        // What is wanted now is `a - A`, but SUB computes `dst - src` and its
        // destination must be a register — and there may be no free one, since both
        // operands can be in registers while A holds the product. Negating instead
        // turns it into an addition, whose source may be memory, so the whole
        // sequence touches nothing but A. Anything else live here is already
        // forbidden from A by the interference graph.
        // XOR rather than NOT, for the reason given in selectUn: NOT leaves 65536 in
        // the register when its operand is 0xFFFF, and a product of 0xFFFF is reachable
        // (a = 0xFFFF, b = 1). Nothing spills between here and the ADD, so today the
        // out-of-range value would survive to be wrapped correctly -- but it is one
        // scheduling change away from being stored, and a store of 65536 faults.
        out.add(new Asm.Insn("XOR", List.of(reg(Asm.Reg.A), new Asm.Imm(0xFFFF)), null));
        out.add(new Asm.Insn("INC", List.of(reg(Asm.Reg.A)), "negate the product"));
        out.add(new Asm.Insn("ADD", List.of(reg(Asm.Reg.A), operandOf(instruction.lhs())),
                "a - (a / b) * b"));
        writeResult(instruction.dst(), Asm.Reg.A);
    }

    private void selectUn(Ir.Un instruction) {
        Asm.Reg home = homeOf(instruction.dst());
        switch (instruction.op()) {
            case NOT -> {
                // XOR with all ones rather than the NOT instruction, which is the same
                // one instruction and two bytes larger, because NOT is broken at the
                // top of the range. The simulator computes JavaScript's ~operand and
                // then wraps a negative result with 65536 - ((-value) % 65536); for
                // 0xFFFF that is ~65535 = -65536, and the formula yields 65536 rather
                // than 0. Registers are not masked on assignment, so the register then
                // holds a value no store will accept: `~x` where x is 0xFFFF faulted
                // the machine with "Invalid data value 65536" at -O0, and only escaped
                // at -O1 because the value happened not to be stored.
                Asm.Reg target = (home != null) ? home : Asm.Reg.A;
                moveInto(target, instruction.src());
                out.add(new Asm.Insn("XOR", List.of(reg(target), new Asm.Imm(0xFFFF)), null));
                writeResult(instruction.dst(), target);
                return;
            }
            case NEG -> {
                // Two's complement negation: 0 - x. SUB is not commutative, and the
                // destination cannot be the operand's own register, so this always
                // goes through A unless the destination is somewhere else entirely.
                Asm.Reg target = (home != null && !alreadyIn(instruction.src(), home))
                        ? home : Asm.Reg.A;
                out.add(new Asm.Insn("MOV", List.of(reg(target), new Asm.Imm(0)), null));
                out.add(new Asm.Insn("SUB",
                        List.of(reg(target), operandOf(instruction.src())), "negate"));
                writeResult(instruction.dst(), target);
                return;
            }
            case LNOT -> {
                // Lowering turns !x into (x == 0), so this should not arrive here.
                throw new IllegalStateException("logical not is lowered to a comparison");
            }
        }
        storeAInto(instruction.dst());
    }

    /**
     * The address of a frame slot, an argument, or a global.
     *
     * <p>A label used as a plain operand assembles to its address, so a global needs
     * no arithmetic; a frame slot is the frame pointer displaced by its offset.
     */
    private void selectAddrOf(Ir.AddrOf instruction) {
        switch (instruction.addr()) {
            case Ir.Addr.Global g ->
                    out.add(new Asm.Insn("MOV",
                            List.of(reg(Asm.Reg.A), new Asm.LabelRef(g.label())), null));
            case Ir.Addr.Local local ->
                    emitFrameAddress(FrameLayout.slotOffset(local.slot()) + local.offset());
            case Ir.Addr.Arg arg -> emitFrameAddress(FrameLayout.argOffset(arg.index()));
            case Ir.Addr.Mem mem -> {
                loadIntoA(mem.base());
                if (mem.offset() != 0) {
                    out.add(new Asm.Insn("ADD",
                            List.of(reg(Asm.Reg.A), new Asm.Imm(mem.offset())), null));
                }
            }
        }
        writeResult(instruction.dst(), Asm.Reg.A);
    }

    /**
     * A = D + offset. The negative case uses SUB, because the assembler rejects a
     * negative immediate outright.
     */
    private void emitFrameAddress(int offset) {
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), reg(Asm.Reg.D)), null));
        if (offset > 0) {
            out.add(new Asm.Insn("ADD", List.of(reg(Asm.Reg.A), new Asm.Imm(offset)), null));
        } else if (offset < 0) {
            out.add(new Asm.Insn("SUB", List.of(reg(Asm.Reg.A), new Asm.Imm(-offset)), null));
        }
    }

    /**
     * Turns the zero-extended byte in {@code A} into a sign-extended word.
     *
     * <p>{@code (v ^ 0x80) - 0x80}, which is the standard branchless way and the only
     * one worth having here: the alternative tests bit 7 and jumps, and a branch in
     * the middle of a load is two instructions plus a pipeline this machine does not
     * have. 156 becomes 65436, which is -100; 100 stays 100.
     */
    private void extendSign() {
        out.add(new Asm.Insn("XOR", List.of(reg(Asm.Reg.A), new Asm.Imm(0x80)),
                "sign-extend: (v ^ 0x80) - 0x80"));
        out.add(new Asm.Insn("SUB", List.of(reg(Asm.Reg.A), new Asm.Imm(0x80)), null));
    }

    private void selectLoad(Ir.Load instruction) {
        // An indirect access needs its base in a register, and A is the one that is
        // always free to be it: A is never a colour, so nothing live is ever sitting
        // there. Reading through it into itself is legal, which is what lets the
        // value land in A afterwards.
        if (instruction.addr() instanceof Ir.Addr.Mem mem) {
            Asm.Reg base = baseRegisterFor(mem.base());
            Asm.Operand through = new Asm.Indirect(base, mem.offset());
            if (instruction.width() == Ir.Width.BYTE) {
                // The byte first, then the high half: zeroing A first would destroy
                // the base when the base is A.
                out.add(new Asm.Insn("MOVB", List.of(reg(Asm.Reg.AL), through), null));
                out.add(new Asm.Insn("MOVB", List.of(reg(Asm.Reg.AH), new Asm.Imm(0)),
                        "zero-extend"));
                if (instruction.signed()) extendSign();
            } else {
                out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), through), null));
            }
            writeResult(instruction.dst(), Asm.Reg.A);
            return;
        }

        Asm.Operand source = addressOperand(instruction.addr());
        if (instruction.width() == Ir.Width.BYTE) {
            // Zero-extend: clear the register, then move the byte into its low half.
            // A signed byte has to go through A, because sign extension is arithmetic
            // on the whole sixteen bits and A is where arithmetic happens.
            Asm.Reg home = instruction.signed() ? null : homeOf(instruction.dst());
            Asm.Reg target = (home != null) ? home : Asm.Reg.A;
            out.add(new Asm.Insn("MOV", List.of(reg(target), new Asm.Imm(0)), null));
            out.add(new Asm.Insn("MOVB", List.of(reg(target.low()), source), null));
            if (instruction.signed()) extendSign();
            writeResult(instruction.dst(), target);
            return;
        }
        Asm.Reg home = homeOf(instruction.dst());
        if (home != null) {
            out.add(new Asm.Insn("MOV", List.of(reg(home), source), null));
            return;
        }
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), source), null));
        storeAInto(instruction.dst());
    }

    private void selectStore(Ir.Store instruction) {
        if (instruction.addr() instanceof Ir.Addr.Mem mem) {
            Asm.Reg base = baseRegisterFor(mem.base());
            Asm.Operand through = new Asm.Indirect(base, mem.offset());

            // An immediate word goes straight through, with no register at all.
            if (instruction.width() == Ir.Width.WORD
                    && instruction.src() instanceof Ir.Imm imm) {
                out.add(new Asm.Insn("MOV", List.of(through, new Asm.Imm(imm.value())), null));
                return;
            }

            // Otherwise the value needs a register that is not the base, and taking
            // one means knowing what is live: the whole point is not to overwrite a
            // value the surrounding code is still going to read.
            Asm.Reg value = registerForValue(instruction.src(), base);
            boolean borrowed = value == null;
            if (borrowed) {
                // The base, and two values read later: nowhere left. Borrow a register
                // around the store, which the stack reserve's margin covers.
                value = base == Asm.Reg.B ? Asm.Reg.C : Asm.Reg.B;
                out.add(new Asm.Insn("PUSH", List.of(reg(value)), "borrow a register for the store"));
            }
            moveInto(value, instruction.src());
            if (instruction.width() == Ir.Width.BYTE) {
                out.add(new Asm.Insn("MOVB", List.of(through, reg(value.low())), null));
            } else {
                out.add(new Asm.Insn("MOV", List.of(through, reg(value)), null));
            }
            if (borrowed) out.add(new Asm.Insn("POP", List.of(reg(value)), null));
            return;
        }

        Asm.Operand target = addressOperand(instruction.addr());

        if (instruction.width() == Ir.Width.WORD && instruction.src() instanceof Ir.Imm imm) {
            out.add(new Asm.Insn("MOV", List.of(target, new Asm.Imm(imm.value())), null));
            return;
        }
        // MOV [mem], reg is legal, so a value already in a register needs no detour.
        if (instruction.width() == Ir.Width.WORD
                && instruction.src() instanceof Ir.VReg source && homeOf(source) != null) {
            out.add(new Asm.Insn("MOV", List.of(target, reg(homeOf(source))), null));
            return;
        }
        loadIntoA(instruction.src());
        if (instruction.width() == Ir.Width.BYTE) {
            out.add(new Asm.Insn("MOVB", List.of(target, reg(Asm.Reg.AL)), null));
        } else {
            out.add(new Asm.Insn("MOV", List.of(target, reg(Asm.Reg.A)), null));
        }
    }

    private void selectRet(Ir.Ret instruction) {
        if (instruction.value() != null) {
            moveInto(Asm.Reg.A, instruction.value());
        }
        // The epilogue follows immediately for the single trailing return, so the
        // jump is only emitted when something else comes after it.
        epilogueUsed = true;
        out.add(new Asm.Insn("JMP", List.of(new Asm.LabelRef(epilogueLabel)), "return"));
    }

    /**
     * A call, following the convention verified against the real machine.
     *
     * <p>Arguments are pushed in reverse so the first one ends up nearest the frame
     * base at {@code [D+5]}; {@code CALL} pushes the return address; and the caller
     * reclaims the argument bytes afterwards. The result comes back in {@code A}.
     *
     * <p>{@code PUSH} takes only a register or an immediate — never a memory operand —
     * so an argument living in a frame slot has to go through {@code A} first.
     */
    private void selectCall(Ir.Call instruction) {
        List<Ir.Value> args = instruction.args();

        // Arguments 1 and up go on the stack, deepest first, so argument 1 ends up
        // nearest. Argument 0 goes into B — and it goes there LAST, after every push
        // is done, because the pushes only touch A and SP and so cannot disturb it,
        // whereas setting B first would be clobbered by anything computed after.
        for (int i = args.size() - 1; i >= 1; i--) {
            Ir.Value argument = args.get(i);
            if (argument instanceof Ir.Imm imm) {
                out.add(new Asm.Insn("PUSH", List.of(new Asm.Imm(imm.value())),
                        "arg " + i));
            } else {
                loadIntoA(argument);
                out.add(new Asm.Insn("PUSH", List.of(reg(Asm.Reg.A)), "arg " + i));
            }
        }
        // Where the call goes. A call through a pointer loads it into A, which the
        // pushes are done with and B's argument does not need — and before B is set,
        // since the pointer may be sitting in B. CALL [A] goes to the address in A.
        Asm.Operand target;
        if (!instruction.isIndirect()) {
            target = new Asm.LabelRef(instruction.target());
        } else if (instruction.callee() instanceof Ir.GlobalRef known) {
            target = new Asm.LabelRef(known.label());
        } else {
            loadIntoA(instruction.callee());
            target = new Asm.Indirect(Asm.Reg.A, 0);
        }
        if (!args.isEmpty()) {
            // Free when the allocator already had it there, which for a value
            // computed just before the call is the common case.
            moveInto(Asm.Reg.B, args.get(0));
        }

        out.add(new Asm.Insn("CALL", List.of(target), null));

        int pushed = Math.max(0, args.size() - 1);
        if (pushed > 0) {
            out.add(new Asm.Insn("ADD", List.of(reg(Asm.Reg.SP), new Asm.Imm(pushed * 2)),
                    "reclaim arguments"));
        }
        if (instruction.dst() != null) {
            writeResult(instruction.dst(), Asm.Reg.A);
        }
    }

    /**
     * An argument that arrived in {@code B}.
     *
     * <p>Emits nothing at all when the allocator gave the value {@code B} as its home,
     * which is what makes a leaf function's first argument free. Otherwise it is the
     * one move or store that puts it where the function keeps it.
     */
    private void selectArgIn(Ir.ArgIn instruction) {
        writeResult(instruction.dst(), Asm.Reg.B);
    }

    /**
     * A jump through a table of block addresses.
     *
     * <p>Deliberately uses nothing but {@code A}. This is a terminator, so values
     * live in the successors are still live here, and touching {@code B} or
     * {@code C} would clobber one — {@code A} is the reserved scratch and cannot.
     *
     * <p>Note {@code MOV A, [A]} is the load: a bracketed register is an address to
     * read from, whereas {@code JMP [A]} transfers to the address in {@code A}
     * without loading. The two brackets mean different things.
     */
    private void selectTableBr(Ir.TableBr instruction) {
        moveInto(Asm.Reg.A, instruction.index());
        out.add(new Asm.Insn("SHL", List.of(reg(Asm.Reg.A), new Asm.Imm(1)),
                "two bytes per entry"));
        out.add(new Asm.Insn("ADD", List.of(reg(Asm.Reg.A),
                new Asm.LabelRef(instruction.tableLabel())), null));
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), new Asm.Indirect(Asm.Reg.A, 0)),
                "load the target"));
        out.add(new Asm.Insn("JMP", List.of(new Asm.Indirect(Asm.Reg.A, 0)), null));
    }

    /* ---------------- port I/O ---------------- */

    /**
     * Reads an I/O port into {@code A}.
     *
     * <p>The operand form matters more than it looks. {@code IN imm} and
     * {@code IN reg} take the port number from the immediate or the register, but
     * {@code IN [addr]} is a <em>double</em> indirection: it loads a word from memory
     * and uses <em>that</em> as the port number. So a spilled port must be brought
     * into a register first rather than handed over as a memory operand.
     */
    private void selectIn(Ir.In instruction) {
        Asm.Operand port = portOperand(instruction.port());
        boolean borrowed = port == null;
        if (borrowed) {
            // No scratch register is free, so borrow B around the instruction.
            out.add(new Asm.Insn("PUSH", List.of(reg(Asm.Reg.B)), "borrow a port register"));
            out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.B), operandOf(instruction.port())), null));
            port = reg(Asm.Reg.B);
        }
        out.add(new Asm.Insn("IN", List.of(port), null));
        if (borrowed) out.add(new Asm.Insn("POP", List.of(reg(Asm.Reg.B)), null));
        writeResult(instruction.dst(), Asm.Reg.A);
    }

    /** Writes {@code A} to an I/O port. */
    private void selectOut(Ir.Out instruction) {
        // The value goes into A first, so that borrowing B below cannot destroy it.
        moveInto(Asm.Reg.A, instruction.value());

        Asm.Operand port = portOperand(instruction.port());
        if (port == null) {
            out.add(new Asm.Insn("PUSH", List.of(reg(Asm.Reg.B)), "borrow a port register"));
            out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.B), operandOf(instruction.port())), null));
            out.add(new Asm.Insn("OUT", List.of(reg(Asm.Reg.B)), null));
            out.add(new Asm.Insn("POP", List.of(reg(Asm.Reg.B)), null));
            return;
        }
        out.add(new Asm.Insn("OUT", List.of(port), null));
    }

    /** A port operand that is safe to use directly, or null if it must be borrowed. */
    private Asm.Operand portOperand(Ir.Value port) {
        if (port instanceof Ir.Imm imm) return new Asm.Imm(imm.value());
        if (port instanceof Ir.VReg vreg && homeOf(vreg) != null) {
            return reg(homeOf(vreg));
        }
        return null;
    }

    /* ---------------- comparisons ---------------- */

    /**
     * Emits the CMP for a condition, leaving the flags set.
     *
     * <p>The machine has no sign flag and only unsigned branches, so a signed
     * comparison is synthesised by biasing both operands by 0x8000, which maps the
     * signed order onto the unsigned one.
     *
     * <p>The right side is biased too. A constant is biased here, at no cost. Anything
     * else needs a register of its own, and it has to be one the code after this does
     * not read. It used to be {@code B}, always — which destroyed whatever the
     * allocator had put there, and a value computed before a signed comparison and
     * used after it came back as the biased constant. The program fuzzer found it.
     */
    private void emitCompare(Ir.Cond cond, Ir.Value lhs, Ir.Value rhs) {
        if (!cond.isSigned()) {
            // CMP needs its left side in a register; if it is already in one, use it
            // rather than copying into A and clobbering the accumulator.
            if (lhs instanceof Ir.VReg vreg && homeOf(vreg) != null) {
                out.add(new Asm.Insn("CMP",
                        List.of(reg(homeOf(vreg)), operandOf(rhs)), null));
                return;
            }
            moveInto(Asm.Reg.A, lhs);
            out.add(new Asm.Insn("CMP", List.of(reg(Asm.Reg.A), operandOf(rhs)), null));
            return;
        }
        moveInto(Asm.Reg.A, lhs);
        out.add(new Asm.Insn("XOR", List.of(reg(Asm.Reg.A), new Asm.Imm(0x8000)),
                "bias for signed compare"));
        if (rhs instanceof Ir.Imm imm) {
            out.add(new Asm.Insn("CMP", List.of(reg(Asm.Reg.A),
                    new Asm.Imm((imm.value() ^ 0x8000) & 0xFFFF)), null));
            return;
        }
        Asm.Reg scratch = scratchFor(rhs);
        boolean borrowed = scratch == null;
        if (borrowed) {
            // B and C both hold values read later. Borrow one around the compare:
            // PUSH and POP leave the flags alone, so the POP can follow the CMP, and
            // the two bytes are within the margin the stack reserve keeps, as the
            // port register's borrow is.
            scratch = Asm.Reg.B;
            out.add(new Asm.Insn("PUSH", List.of(reg(scratch)), "borrow a register for the bias"));
        }
        moveInto(scratch, rhs);
        out.add(new Asm.Insn("XOR", List.of(reg(scratch), new Asm.Imm(0x8000)), null));
        out.add(new Asm.Insn("CMP", List.of(reg(Asm.Reg.A), reg(scratch)), null));
        if (borrowed) out.add(new Asm.Insn("POP", List.of(reg(scratch)), null));
    }

    /**
     * A register the right side of a signed compare can be biased in: its own, if
     * nothing reads it afterwards, or any other the code after this does not need.
     * Null when B and C are both still wanted.
     */
    private Asm.Reg scratchFor(Ir.Value rhs) {
        Set<Asm.Reg> busy = registersStillLive();
        if (rhs instanceof Ir.VReg vreg) {
            Asm.Reg home = homeOf(vreg);
            if (home == Asm.Reg.B || home == Asm.Reg.C) {
                if (!busy.contains(home)) return home;
            }
        }
        for (Asm.Reg candidate : List.of(Asm.Reg.B, Asm.Reg.C)) {
            if (!busy.contains(candidate)) return candidate;
        }
        return null;
    }

    /** The conditional jump that is taken when {@code cond} holds. */
    private static String jumpFor(Ir.Cond cond) {
        return switch (cond) {
            case EQ -> "JZ";
            case NE -> "JNZ";
            // After biasing, a signed comparison uses the unsigned jumps.
            case ULT, SLT -> "JC";
            case UGE, SGE -> "JNC";
            case UGT, SGT -> "JA";
            case ULE, SLE -> "JNA";
        };
    }

    private void selectCbr(Ir.Cbr instruction) {
        emitCompare(instruction.cond(), instruction.lhs(), instruction.rhs());
        out.add(new Asm.Insn(jumpFor(instruction.cond()),
                List.of(new Asm.LabelRef(instruction.ifTrue())), null));
        out.add(new Asm.Insn("JMP", List.of(new Asm.LabelRef(instruction.ifFalse())), null));
    }

    /**
     * Materialises a comparison as 0 or 1. Only used where a truth value is stored
     * or combined; a condition controlling a branch goes through {@link Ir.Cbr}
     * instead and never builds the value at all.
     */
    private void selectCmp(Ir.Cmp instruction) {
        String setLabel = labels.internal("cmp_set");
        String endLabel = labels.internal("cmp_end");

        emitCompare(instruction.cond(), instruction.lhs(), instruction.rhs());
        Asm.Operand destination = slotOf(instruction.dst());
        out.add(new Asm.Insn(jumpFor(instruction.cond()),
                List.of(new Asm.LabelRef(setLabel)), null));
        out.add(new Asm.Insn("MOV", List.of(destination, new Asm.Imm(0)), null));
        out.add(new Asm.Insn("JMP", List.of(new Asm.LabelRef(endLabel)), null));
        out.add(new Asm.Label(setLabel));
        out.add(new Asm.Insn("MOV", List.of(destination, new Asm.Imm(1)), null));
        out.add(new Asm.Label(endLabel));
    }

    /* ---------------- operand helpers ---------------- */

    private void loadIntoA(Ir.Value value) {
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), operandOf(value)), null));
    }

    private void storeAInto(Ir.VReg vreg) {
        out.add(new Asm.Insn("MOV", List.of(slotOf(vreg), reg(Asm.Reg.A)), null));
    }

    private Asm.Operand operandOf(Ir.Value value) {
        return switch (value) {
            case Ir.Imm imm -> new Asm.Imm(imm.value());
            case Ir.VReg vreg -> slotOf(vreg);
            case Ir.GlobalRef ref -> new Asm.LabelRef(ref.label());
        };
    }

    /** Where a value lives: its allocated register, or its frame slot. */
    private Asm.Operand slotOf(Ir.VReg vreg) {
        Asm.Reg register = homeOf(vreg);
        if (register != null) return new Asm.Register(register);
        return FrameLayout.local(slots.slotOf(vreg));
    }

    /** The register a value was allocated, or null if it lives in memory. */
    private Asm.Reg homeOf(Ir.VReg vreg) {
        return allocation == null ? null : allocation.registerFor(vreg);
    }

    /** True when the value is already sitting in {@code register}. */
    private boolean alreadyIn(Ir.Value value, Asm.Reg register) {
        return value instanceof Ir.VReg vreg && homeOf(vreg) == register;
    }

    /** Emits {@code MOV register, value}, skipping it when that is a no-op. */
    private void moveInto(Asm.Reg register, Ir.Value value) {
        if (alreadyIn(value, register)) return;
        out.add(new Asm.Insn("MOV", List.of(reg(register), operandOf(value)), null));
    }

    /** Writes a computed result from {@code computedIn} to where it belongs. */
    private void writeResult(Ir.VReg dst, Asm.Reg computedIn) {
        Asm.Reg home = homeOf(dst);
        if (home == computedIn) return;                       // already in place
        if (home != null) {
            out.add(new Asm.Insn("MOV", List.of(reg(home), reg(computedIn)), null));
            return;
        }
        out.add(new Asm.Insn("MOV",
                List.of(FrameLayout.local(slots.slotOf(dst)), reg(computedIn)), null));
    }

    /**
     * Swaps a commutative operation's operands when that lets it accumulate in place.
     *
     * <p>The situation is common and costs two instructions when it is missed. The
     * destination's register already holds the RIGHT operand, so computing there
     * would clobber it before it is read, and {@link #computeIn} falls back to A:
     *
     * <pre>
     *   MOV A, [SP+3]      instead of      ADD B, [SP+3]
     *   ADD A, B
     *   MOV B, A
     * </pre>
     *
     * <p>But addition does not care which way round its operands are. Turning
     * {@code b = x + b} into {@code b = b + x} makes the left operand the one already
     * in place, so the move in and the move out both disappear. Only for the
     * operations where it is sound: {@code x - b} is not {@code b - x}.
     */
    private Ir.Bin accumulateInPlace(Ir.Bin instruction) {
        if (!instruction.op().isCommutative()) return instruction;
        Asm.Reg home = homeOf(instruction.dst());
        if (home == null) return instruction;
        if (instruction.rhs().equals(instruction.lhs())) return instruction;
        if (!alreadyIn(instruction.rhs(), home)) return instruction;
        if (alreadyIn(instruction.lhs(), home)) return instruction;

        return new Ir.Bin(instruction.dst(), instruction.op(),
                instruction.rhs(), instruction.lhs());
    }

    /**
     * Where to compute a result.
     *
     * <p>Preferring the destination's own register lets the operation write straight
     * there, which is what makes coalescing pay off. It falls back to A when the
     * destination register also holds the right operand, since loading the left one
     * first would clobber it — which {@link #accumulateInPlace} has already avoided
     * wherever the operation allows it.
     */
    private Asm.Reg computeIn(Ir.VReg dst, Ir.Value lhs, Ir.Value rhs) {
        Asm.Reg home = homeOf(dst);
        if (home == null) return Asm.Reg.A;
        if (alreadyIn(rhs, home) && !rhs.equals(lhs)) return Asm.Reg.A;
        return home;
    }

    private Asm.Operand addressOperand(Ir.Addr addr) {
        return switch (addr) {
            case Ir.Addr.Local local -> FrameLayout.local(local.slot(), local.offset());
            case Ir.Addr.Arg arg -> FrameLayout.argument(arg.index());
            case Ir.Addr.Global global -> new Asm.Absolute(global.label());
            case Ir.Addr.Mem ignored -> throw new IllegalStateException(
                    "indirect access is handled by selectLoad and selectStore");
        };
    }

    /**
     * Puts an address into a register usable as an indirect base.
     *
     * <p>A pointer already in a register is used where it is. A spilled one is loaded
     * into {@code A}, and that choice is the whole correctness argument: colouring
     * hands out only {@code B} and {@code C}, so {@code A} is the one register that
     * never holds a value the surrounding code will read again.
     *
     * <p>This used to load into {@code B} unconditionally, which quietly destroyed
     * whatever the allocator had put there. A loop holding a counter in {@code B} and
     * storing through a spilled pointer — {@code n->value = count;} — incremented the
     * pointer instead of the counter and returned it, at {@code -O1} only.
     */
    private Asm.Reg baseRegisterFor(Ir.VReg pointer) {
        Asm.Reg home = homeOf(pointer);
        if (home != null && home != Asm.Reg.SP) return home;
        out.add(new Asm.Insn("MOV", List.of(reg(Asm.Reg.A), operandOf(pointer)), null));
        return Asm.Reg.A;
    }

    /**
     * A register to carry a value being stored, other than the one holding the base.
     *
     * <p>Its own register if it has one. Otherwise the first that holds nothing the
     * code after this instruction still needs — which is what {@code liveness} is
     * here to answer, and the only way to pick a scratch that is safe rather than
     * merely usually safe. Null when there is none, and the caller must borrow.
     */
    private Asm.Reg registerForValue(Ir.Value value, Asm.Reg base) {
        if (value instanceof Ir.VReg vreg) {
            Asm.Reg home = homeOf(vreg);
            if (home != null && home != Asm.Reg.SP && home != base) return home;
        }
        Set<Asm.Reg> busy = registersStillLive();
        for (Asm.Reg candidate : List.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C)) {
            if (candidate != base && !busy.contains(candidate)) return candidate;
        }
        // Would need a fourth register: the base, two live values, and this one. The
        // caller borrows one around the store. This used to throw, and a struct
        // initialized with a list while two values were live was enough to reach it —
        // which the program fuzzer did.
        return null;
    }

    /** Which registers hold values the code after the current instruction reads. */
    private Set<Asm.Reg> registersStillLive() {
        Set<Asm.Reg> busy = new HashSet<>();
        if (liveAfter == null || allocation == null) return busy;
        for (Ir.VReg vreg : liveAfter) {
            Asm.Reg home = allocation.registerFor(vreg);
            if (home != null) busy.add(home);
        }
        return busy;
    }

    private static Asm.Operand reg(Asm.Reg register) {
        return new Asm.Register(register);
    }
}
