package dev.madlador.parser;

import dev.madlador.lexer.TokenType;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.LogicalOperation;

/**
 * Binding powers for the expression parser, lowest first.
 *
 * <p>Follows C, so that anyone who knows C is not surprised — including C's
 * notorious choice of putting the bitwise operators <em>below</em> the comparisons,
 * which is why {@code a & 1 == 0} means {@code a & (1 == 0)}.
 */
public final class Precedence {

    public static final int NONE = 0;
    public static final int COMMA_EXPR = 1;   // a, b
    public static final int ASSIGNMENT = 2;   // = += -= ...     (right associative)
    public static final int CONDITIONAL = 3;  // ?:              (right associative)
    public static final int LOGICAL_OR = 4;   // ||
    public static final int LOGICAL_AND = 5;  // &&
    public static final int BIT_OR = 6;       // |
    public static final int BIT_XOR = 7;      // ^
    public static final int BIT_AND = 8;      // &
    public static final int EQUALITY = 9;     // == !=
    public static final int RELATIONAL = 10;  // < <= > >=
    public static final int SHIFT = 11;       // << >>
    public static final int ADDITIVE = 12;    // + -
    public static final int MULTIPLICATIVE = 13; // * / %
    public static final int UNARY = 14;       // - + ! ~
    public static final int POSTFIX = 15;     // () []

    private Precedence() {
    }

    /** The binding power of {@code type} as an infix operator, or {@link #NONE}. */
    public static int of(TokenType type) {
        return switch (type) {
            case ASSIGN, PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,
                 PERCENT_ASSIGN, AMP_ASSIGN, PIPE_ASSIGN, CARET_ASSIGN,
                 SHL_ASSIGN, SHR_ASSIGN -> ASSIGNMENT;
            case COMMA -> COMMA_EXPR;
            case QUESTION -> CONDITIONAL;
            case OR_OR -> LOGICAL_OR;
            case AND_AND -> LOGICAL_AND;
            case PIPE -> BIT_OR;
            case CARET -> BIT_XOR;
            case AMPERSAND -> BIT_AND;
            case EQUAL, NOT_EQUAL -> EQUALITY;
            case LESS, LESS_EQUAL, GREATER, GREATER_EQUAL -> RELATIONAL;
            case SHIFT_LEFT, SHIFT_RIGHT -> SHIFT;
            case PLUS, DASH -> ADDITIVE;
            case STAR, SLASH, PERCENT -> MULTIPLICATIVE;
            default -> NONE;
        };
    }

    /** Assignment and the conditional are the right-associative levels. */
    public static boolean isRightAssociative(int level) {
        return level == ASSIGNMENT || level == CONDITIONAL;
    }

    /** The plain binary operator for an infix token, or null if it is not one. */
    public static BinaryOperation binaryOperation(TokenType type) {
        return switch (type) {
            case PLUS -> BinaryOperation.ADD;
            case DASH -> BinaryOperation.SUBTRACT;
            case STAR -> BinaryOperation.MULTIPLY;
            case SLASH -> BinaryOperation.DIVIDE;
            case PERCENT -> BinaryOperation.MODULO;
            case AMPERSAND -> BinaryOperation.BIT_AND;
            case PIPE -> BinaryOperation.BIT_OR;
            case CARET -> BinaryOperation.BIT_XOR;
            case SHIFT_LEFT -> BinaryOperation.SHIFT_LEFT;
            case SHIFT_RIGHT -> BinaryOperation.SHIFT_RIGHT;
            case EQUAL -> BinaryOperation.EQUAL;
            case NOT_EQUAL -> BinaryOperation.NOT_EQUAL;
            case LESS -> BinaryOperation.LESS;
            case LESS_EQUAL -> BinaryOperation.LESS_EQUAL;
            case GREATER -> BinaryOperation.GREATER;
            case GREATER_EQUAL -> BinaryOperation.GREATER_EQUAL;
            default -> null;
        };
    }

    public static LogicalOperation logicalOperation(TokenType type) {
        return switch (type) {
            case AND_AND -> LogicalOperation.AND;
            case OR_OR -> LogicalOperation.OR;
            default -> null;
        };
    }

    /**
     * For a compound assignment token, the operation it applies;
     * null for plain {@code =}, which is not compound.
     */
    public static BinaryOperation compoundOperation(TokenType type) {
        return switch (type) {
            case PLUS_ASSIGN -> BinaryOperation.ADD;
            case MINUS_ASSIGN -> BinaryOperation.SUBTRACT;
            case STAR_ASSIGN -> BinaryOperation.MULTIPLY;
            case SLASH_ASSIGN -> BinaryOperation.DIVIDE;
            case PERCENT_ASSIGN -> BinaryOperation.MODULO;
            case AMP_ASSIGN -> BinaryOperation.BIT_AND;
            case PIPE_ASSIGN -> BinaryOperation.BIT_OR;
            case CARET_ASSIGN -> BinaryOperation.BIT_XOR;
            case SHL_ASSIGN -> BinaryOperation.SHIFT_LEFT;
            case SHR_ASSIGN -> BinaryOperation.SHIFT_RIGHT;
            default -> null;
        };
    }

    public static boolean isAssignment(TokenType type) {
        return of(type) == ASSIGNMENT;
    }
}
