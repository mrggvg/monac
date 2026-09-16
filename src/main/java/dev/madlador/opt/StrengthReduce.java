package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.List;

/**
 * Rewrites multiplication, division and remainder by a power of two.
 *
 * <p>Worth being precise about why, because the usual reason does not apply here.
 * Every instruction on this machine costs exactly one clock tick, so {@code MUL 2}
 * and {@code SHL x, 1} are equally fast — and {@code MUL WORD} is actually the
 * <em>smaller</em> encoding. Replacing a multiply with a shift buys no speed at all.
 *
 * <p>Two things do make it worthwhile:
 * <ul>
 *   <li>{@code MUL} and {@code DIV} implicitly read and write {@code A}, so removing
 *       one frees the accumulator and reduces pressure on a three-register file.</li>
 *   <li>{@code x % 2^k} becomes a single {@code AND}, replacing the six-instruction
 *       sequence that a general remainder needs on a machine with no MOD.</li>
 * </ul>
 */
public final class StrengthReduce implements Pass {

    @Override
    public String name() {
        return "strength-reduce";
    }

    @Override
    public boolean run(Ir.Function function) {
        boolean changed = false;
        for (Ir.BasicBlock block : function.blocks()) {
            List<Ir.Instr> instructions = block.instructions();
            for (int i = 0; i < instructions.size(); i++) {
                if (!(instructions.get(i) instanceof Ir.Bin bin)) continue;
                Ir.Bin canonical = canonicalize(bin);
                Ir.Instr rewritten = rewrite(canonical);
                if (rewritten == canonical && canonical != bin) rewritten = canonical;
                if (rewritten != bin) {
                    instructions.set(i, rewritten);
                    changed = true;
                }
            }
        }
        return changed;
    }

    /**
     * Puts a constant operand on the right of a commutative operation.
     *
     * <p>Every rule below matches on the right operand, so without this
     * {@code 8 * x} would keep its multiply while {@code x * 8} became a shift —
     * the same expression compiling differently depending on which way round it was
     * typed. It also lets the instruction selector fold the constant as an immediate
     * rather than loading it into a register first.
     */
    private static Ir.Bin canonicalize(Ir.Bin bin) {
        if (!bin.op().isCommutative()) return bin;
        if (!(bin.lhs() instanceof Ir.Imm)) return bin;
        if (bin.rhs() instanceof Ir.Imm) return bin;      // constant folding's job
        return new Ir.Bin(bin.dst(), bin.op(), bin.rhs(), bin.lhs());
    }

    private static Ir.Instr rewrite(Ir.Bin bin) {
        if (!(bin.rhs() instanceof Ir.Imm imm)) return bin;
        int value = imm.value();

        switch (bin.op()) {
            case MUL -> {
                if (value == 0) return new Ir.Const(bin.dst(), 0);
                if (value == 1) return new Ir.Copy(bin.dst(), bin.lhs());
                int shift = log2(value);
                if (shift >= 0) return new Ir.Bin(bin.dst(), Ir.BinOp.SHL, bin.lhs(), new Ir.Imm(shift));
            }
            case DIV -> {
                if (value == 1) return new Ir.Copy(bin.dst(), bin.lhs());
                int shift = log2(value);
                if (shift >= 0) return new Ir.Bin(bin.dst(), Ir.BinOp.SHR, bin.lhs(), new Ir.Imm(shift));
            }
            case MOD -> {
                if (value == 1) return new Ir.Const(bin.dst(), 0);
                // The big one: x % 2^k is x & (2^k - 1), which is one instruction
                // instead of the divide-multiply-subtract dance.
                if (log2(value) >= 0) {
                    return new Ir.Bin(bin.dst(), Ir.BinOp.AND, bin.lhs(), new Ir.Imm(value - 1));
                }
            }
            case ADD, SUB, OR, XOR -> {
                if (value == 0) return new Ir.Copy(bin.dst(), bin.lhs());
            }
            case AND -> {
                if (value == 0) return new Ir.Const(bin.dst(), 0);
                if (value == 0xFFFF) return new Ir.Copy(bin.dst(), bin.lhs());
            }
            case SHL, SHR -> {
                if (value == 0) return new Ir.Copy(bin.dst(), bin.lhs());
            }
        }
        return bin;
    }

    /** The exponent if {@code value} is a power of two, otherwise -1. */
    private static int log2(int value) {
        if (value <= 0 || (value & (value - 1)) != 0) return -1;
        return Integer.numberOfTrailingZeros(value);
    }
}
