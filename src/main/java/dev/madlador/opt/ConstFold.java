package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds constant computations and propagates the results.
 *
 * <p>All arithmetic is unsigned 16-bit, matching the machine — including the
 * division and remainder, which are the two places where getting this wrong would be
 * invisible in small tests and wrong for half the input range.
 *
 * <p>Propagation is per basic block. Without SSA there is no safe way to know which
 * definition reaches a block that has several predecessors, and a block-local
 * analysis captures nearly all of the benefit for code this size.
 */
public final class ConstFold implements Pass {

    @Override
    public String name() {
        return "const-fold";
    }

    @Override
    public boolean run(Ir.Function function) {
        boolean changed = false;
        for (Ir.BasicBlock block : function.blocks()) {
            changed |= runOnBlock(block);
        }
        return changed;
    }

    private boolean runOnBlock(Ir.BasicBlock block) {
        Map<Ir.VReg, Integer> known = new HashMap<>();
        List<Ir.Instr> instructions = block.instructions();
        boolean changed = false;

        for (int i = 0; i < instructions.size(); i++) {
            Ir.Instr replaced = fold(instructions.get(i), known);
            if (replaced != instructions.get(i)) {
                instructions.set(i, replaced);
                changed = true;
            }
            record(instructions.get(i), known);
        }
        return changed;
    }

    /** Substitutes known constants into an instruction, then evaluates if it can. */
    private Ir.Instr fold(Ir.Instr instruction, Map<Ir.VReg, Integer> known) {
        return switch (instruction) {
            case Ir.Bin bin -> {
                Ir.Value lhs = substitute(bin.lhs(), known);
                Ir.Value rhs = substitute(bin.rhs(), known);
                if (lhs instanceof Ir.Imm a && rhs instanceof Ir.Imm b) {
                    Integer value = evaluate(bin.op(), a.value(), b.value());
                    if (value != null) yield new Ir.Const(bin.dst(), value);
                }
                yield (lhs == bin.lhs() && rhs == bin.rhs())
                        ? bin
                        : new Ir.Bin(bin.dst(), bin.op(), lhs, rhs);
            }
            case Ir.Un un -> {
                Ir.Value src = substitute(un.src(), known);
                if (src instanceof Ir.Imm imm) {
                    int value = switch (un.op()) {
                        case NEG -> -imm.value();
                        case NOT -> ~imm.value();
                        case LNOT -> imm.value() == 0 ? 1 : 0;
                    };
                    yield new Ir.Const(un.dst(), value & 0xFFFF);
                }
                yield src == un.src() ? un : new Ir.Un(un.dst(), un.op(), src);
            }
            case Ir.Copy copy -> {
                Ir.Value src = substitute(copy.src(), known);
                if (src instanceof Ir.Imm imm) yield new Ir.Const(copy.dst(), imm.value());
                yield src == copy.src() ? copy : new Ir.Copy(copy.dst(), src);
            }
            case Ir.Store store -> {
                Ir.Value src = substitute(store.src(), known);
                yield src == store.src() ? store : new Ir.Store(store.addr(), src, store.width());
            }
            case Ir.Cmp cmp -> {
                Ir.Value lhs = substitute(cmp.lhs(), known);
                Ir.Value rhs = substitute(cmp.rhs(), known);
                if (lhs instanceof Ir.Imm a && rhs instanceof Ir.Imm b) {
                    yield new Ir.Const(cmp.dst(), compare(cmp.cond(), a.value(), b.value()) ? 1 : 0);
                }
                yield (lhs == cmp.lhs() && rhs == cmp.rhs())
                        ? cmp
                        : new Ir.Cmp(cmp.dst(), cmp.cond(), lhs, rhs);
            }
            case Ir.Cbr cbr -> {
                Ir.Value lhs = substitute(cbr.lhs(), known);
                Ir.Value rhs = substitute(cbr.rhs(), known);
                if (lhs instanceof Ir.Imm a && rhs instanceof Ir.Imm b) {
                    // A condition that is always one way becomes a plain jump.
                    yield new Ir.Br(compare(cbr.cond(), a.value(), b.value())
                            ? cbr.ifTrue() : cbr.ifFalse());
                }
                yield (lhs == cbr.lhs() && rhs == cbr.rhs())
                        ? cbr
                        : new Ir.Cbr(cbr.cond(), lhs, rhs, cbr.ifTrue(), cbr.ifFalse());
            }
            case Ir.Ret ret -> {
                if (ret.value() == null) yield ret;
                Ir.Value value = substitute(ret.value(), known);
                yield value == ret.value() ? ret : new Ir.Ret(value);
            }
            case Ir.Call call -> {
                List<Ir.Value> args = call.args().stream()
                        .map(a -> substitute(a, known)).toList();
                // The callee stays a register even if its value is known: a call to a
                // bare number is not something the selector should be handed.
                yield args.equals(call.args())
                        ? call
                        : new Ir.Call(call.dst(), call.target(), call.callee(), args);
            }
            default -> instruction;
        };
    }

    private static Ir.Value substitute(Ir.Value value, Map<Ir.VReg, Integer> known) {
        if (value instanceof Ir.VReg vreg) {
            Integer constant = known.get(vreg);
            if (constant != null) return new Ir.Imm(constant);
        }
        return value;
    }

    /** Notes what an instruction leaves in a virtual register. */
    private static void record(Ir.Instr instruction, Map<Ir.VReg, Integer> known) {
        switch (instruction) {
            case Ir.Const c -> known.put(c.dst(), c.value() & 0xFFFF);
            case Ir.Copy c -> {
                if (c.src() instanceof Ir.Imm imm) known.put(c.dst(), imm.value());
                else known.remove(c.dst());
            }
            case Ir.Bin b -> known.remove(b.dst());
            case Ir.Un u -> known.remove(u.dst());
            case Ir.Load l -> known.remove(l.dst());
            case Ir.Cmp c -> known.remove(c.dst());
            case Ir.AddrOf a -> known.remove(a.dst());
            case Ir.Call c -> {
                if (c.dst() != null) known.remove(c.dst());
            }
            default -> { }
        }
    }

    /** Returns null where the machine would fault rather than produce a value. */
    static Integer evaluate(Ir.BinOp op, int a, int b) {
        if ((op == Ir.BinOp.DIV || op == Ir.BinOp.MOD) && b == 0) return null;
        int result = switch (op) {
            case ADD -> a + b;
            case SUB -> a - b;
            case MUL -> a * b;
            case DIV -> Integer.divideUnsigned(a, b);
            case MOD -> Integer.remainderUnsigned(a, b);
            case AND -> a & b;
            case OR -> a | b;
            case XOR -> a ^ b;
            case SHL -> a << (b & 0xF);
            case SHR -> a >>> (b & 0xF);
        };
        return result & 0xFFFF;
    }

    static boolean compare(Ir.Cond cond, int a, int b) {
        // Signed conditions compare the values as two's complement.
        short sa = (short) a;
        short sb = (short) b;
        return switch (cond) {
            case EQ -> a == b;
            case NE -> a != b;
            case ULT -> Integer.compareUnsigned(a, b) < 0;
            case ULE -> Integer.compareUnsigned(a, b) <= 0;
            case UGT -> Integer.compareUnsigned(a, b) > 0;
            case UGE -> Integer.compareUnsigned(a, b) >= 0;
            case SLT -> sa < sb;
            case SLE -> sa <= sb;
            case SGT -> sa > sb;
            case SGE -> sa >= sb;
        };
    }
}
