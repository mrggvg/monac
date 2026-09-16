package dev.madlador.sema;

import dev.madlador.parser.ast.Binary;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.Cast;
import dev.madlador.parser.ast.Conditional;
import dev.madlador.parser.ast.Constant;
import dev.madlador.parser.ast.Expression;
import dev.madlador.parser.ast.Identifier;
import dev.madlador.parser.ast.SizeOf;
import dev.madlador.parser.ast.Unary;

import java.util.Optional;

/**
 * Evaluates constant expressions at compile time.
 *
 * <p>Needed for global initializers, which become {@code DW}/{@code DB} directives
 * in the image: there is no startup code that could evaluate them, so anything not
 * foldable has to be rejected rather than silently ignored.
 *
 * <p>All arithmetic is unsigned 16-bit, matching the machine.
 */
public final class ConstantFolder {

    private ConstantFolder() {
    }

    /** The value of {@code expression}, or empty if it is not a constant. */
    public static Optional<Integer> fold(Expression expression) {
        return fold(expression, null);
    }

    /**
     * The same, once analysis has run over {@code expression}: an identifier folds
     * when it names an enum constant or a {@code const} whose initializer folded, a
     * {@code sizeof} folds to the size analysis recorded, a cast narrows, and signed
     * operands divide, shift and compare as signed.
     *
     * <p>Nothing here resolves a name. {@code info} already has, node by node, which
     * is why the folder still needs no scope — the same arrangement {@code sizeof}
     * has always had.
     */
    public static Optional<Integer> fold(Expression expression, SemanticInfo info) {
        return switch (expression) {
            case Constant c -> Optional.of(c.value() & 0xFFFF);

            case Identifier id -> info == null ? Optional.empty()
                    : Optional.ofNullable(info.symbolOf(id)).flatMap(info::constantValueOf);

            case SizeOf z -> info == null ? Optional.empty()
                    : Optional.ofNullable(info.sizeIfRecorded(z));

            case Cast c -> fold(c.operand(), info)
                    .map(v -> narrow(v, info == null ? null : info.typeIfRecorded(c)));

            case Conditional c -> fold(c.condition(), info)
                    .flatMap(v -> fold(v != 0 ? c.then() : c.otherwise(), info));

            case Unary u -> fold(u.operand(), info).map(v -> switch (u.operation()) {
                case PLUS -> v;
                case NEGATE -> (-v) & 0xFFFF;
                case COMPLEMENT -> (~v) & 0xFFFF;
                case NOT -> v == 0 ? 1 : 0;
            });

            case Binary b -> fold(b.left(), info).flatMap(left ->
                    fold(b.right(), info).flatMap(right -> apply(b, left, right, info)));

            default -> Optional.empty();
        };
    }

    /** A value as a cast to {@code to} leaves it, as a 16-bit pattern. */
    private static int narrow(int value, Type to) {
        if (Type.BYTE.equals(to)) return value & 0xFF;
        if (Type.SBYTE.equals(to)) return ((byte) value) & 0xFFFF;
        return value;
    }

    /**
     * Whether an operator works on signed values, decided the way the code generator
     * decides it: a shift by its left operand, anything else by either operand.
     * Without {@code info} nothing is known to be signed, which is how this always
     * folded.
     */
    private static boolean signed(Binary b, SemanticInfo info) {
        if (info == null) return false;
        boolean left = isSigned(info.typeIfRecorded(b.left()));
        boolean right = isSigned(info.typeIfRecorded(b.right()));
        return switch (b.operation()) {
            case SHIFT_LEFT, SHIFT_RIGHT -> left;
            default -> left || right;
        };
    }

    private static boolean isSigned(Type type) {
        return type != null && type.promoted().isSigned();
    }

    private static Optional<Integer> apply(Binary b, int left, int right, SemanticInfo info) {
        // Division by zero is not foldable; it is reported where it appears.
        if ((b.operation() == BinaryOperation.DIVIDE || b.operation() == BinaryOperation.MODULO)
                && right == 0) {
            return Optional.empty();
        }
        boolean signed = signed(b, info);
        short l = (short) left;
        short r = (short) right;
        int result = switch (b.operation()) {
            case ADD -> left + right;
            case SUBTRACT -> left - right;
            case MULTIPLY -> left * right;
            case DIVIDE -> signed ? l / r : Integer.divideUnsigned(left, right);
            case MODULO -> signed ? l % r : Integer.remainderUnsigned(left, right);
            case BIT_AND -> left & right;
            case BIT_OR -> left | right;
            case BIT_XOR -> left ^ right;
            case SHIFT_LEFT -> left << (right & 0xF);
            case SHIFT_RIGHT -> signed ? l >> (right & 0xF) : left >>> (right & 0xF);
            case EQUAL -> left == right ? 1 : 0;
            case NOT_EQUAL -> left != right ? 1 : 0;
            case LESS -> (signed ? Short.compare(l, r) : Integer.compareUnsigned(left, right)) < 0 ? 1 : 0;
            case LESS_EQUAL -> (signed ? Short.compare(l, r) : Integer.compareUnsigned(left, right)) <= 0 ? 1 : 0;
            case GREATER -> (signed ? Short.compare(l, r) : Integer.compareUnsigned(left, right)) > 0 ? 1 : 0;
            case GREATER_EQUAL -> (signed ? Short.compare(l, r) : Integer.compareUnsigned(left, right)) >= 0 ? 1 : 0;
        };
        return Optional.of(result & 0xFFFF);
    }
}
