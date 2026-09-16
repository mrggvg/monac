package dev.madlador.sema;

import dev.madlador.parser.ast.Binary;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.Constant;
import dev.madlador.parser.ast.Expression;

/**
 * The largest value an expression can produce, where that is worth knowing.
 *
 * <p>This exists for one diagnostic: the warning that assigning a {@code word} to a
 * {@code byte} loses the top eight bits. That warning is right about the types and
 * often wrong about the program, because arithmetic promotes to sixteen bits whether
 * or not the result needs them. Writing a digit to the display is the ordinary case:
 *
 * <pre>
 *   screen[i] = '0' + value % 10;
 * </pre>
 *
 * <p>The remainder is at most 9 and {@code '0'} is 48, so the sum is at most 57 and
 * nothing is lost. Warning there teaches the wrong lesson — that the compiler does
 * not understand the program and its diagnostics are noise to be worked around. A
 * language with no cast has no way to say "I know" either, so the compiler has to be
 * the one that knows.
 *
 * <p>Deliberately shallow. It bounds what a bound can be read off directly —
 * literals, masks, remainders, shifts, and arithmetic on those — and gives up on
 * anything that would need to know what a variable holds. Giving up means returning
 * the type's own maximum, which is always true and simply reproduces the old
 * behaviour, so being wrong here can only mean warning where it need not have.
 */
public final class ValueRange {

    /** What a value of unknown provenance can be: anything the type can hold. */
    private static final int UNBOUNDED = 0xFFFF;

    private ValueRange() {
    }

    /** Whether {@code expression} provably fits in {@code max}. */
    public static boolean fitsIn(Expression expression, int max) {
        return Integer.compareUnsigned(upperBound(expression), max) <= 0;
    }

    /** The largest value {@code expression} can produce, or 0xFFFF if unknown. */
    public static int upperBound(Expression expression) {
        return switch (expression) {
            case Constant c -> c.value() & 0xFFFF;
            case Binary b -> boundOf(b);
            default -> UNBOUNDED;
        };
    }

    private static int boundOf(Binary binary) {
        int left = upperBound(binary.left());
        int right = upperBound(binary.right());

        return switch (binary.operation()) {
            // A comparison is 0 or 1, whatever it compared.
            case EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> 1;

            // Clearing bits cannot set any, so the tighter side wins — which is what
            // makes `x & 0xFF` the way to say a value is a byte.
            case BIT_AND -> Integer.min(left, right);

            // Only the bits already present can survive, rounded up to a full mask.
            case BIT_OR, BIT_XOR -> saturate(Integer.max(left, right));

            // A remainder is smaller than its divisor, and only a constant divisor
            // is known here.
            case MODULO -> constant(binary.right()) > 0 ? constant(binary.right()) - 1 : UNBOUNDED;

            case DIVIDE -> constant(binary.right()) > 0
                    ? Integer.divideUnsigned(left, constant(binary.right()))
                    : UNBOUNDED;

            case ADD -> clamp((long) left + right);
            case MULTIPLY -> clamp((long) left * right);

            // Subtraction wraps on this machine, so a - b with b > a is a large
            // number rather than a negative one. No bound to be had.
            case SUBTRACT -> UNBOUNDED;

            // The machine takes the shift amount modulo 16, so `x >> 16` is `x`, not
            // zero. Modelling that faithfully matters: assuming a bigger shift than
            // the hardware performs would bound the result below what it can be.
            case SHIFT_RIGHT -> {
                int by = constant(binary.right());
                yield by < 0 ? UNBOUNDED : left >>> (by & 0xF);
            }
            case SHIFT_LEFT -> {
                int by = constant(binary.right());
                yield by < 0 ? UNBOUNDED : clamp((long) left << (by & 0xF));
            }
        };
    }

    /** The value of a constant subexpression, or -1 if it is not one. */
    private static int constant(Expression expression) {
        return ConstantFolder.fold(expression).orElse(-1);
    }

    /** Anything above what the machine can hold is simply unbounded. */
    private static int clamp(long bound) {
        return bound > UNBOUNDED ? UNBOUNDED : (int) bound;
    }

    /** The smallest all-ones mask that covers {@code bound}: 9 becomes 15. */
    private static int saturate(int bound) {
        int mask = bound;
        mask |= mask >>> 1;
        mask |= mask >>> 2;
        mask |= mask >>> 4;
        mask |= mask >>> 8;
        return mask;
    }
}
