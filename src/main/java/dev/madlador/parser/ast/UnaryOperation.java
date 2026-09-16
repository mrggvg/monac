package dev.madlador.parser.ast;

/** A prefix operator. */
public enum UnaryOperation {
    /** Unary minus: two's complement negation. */
    NEGATE("-"),
    /** Unary plus, which does nothing but is accepted for symmetry. */
    PLUS("+"),
    /** Logical not: 0 becomes 1, anything else becomes 0. */
    NOT("!"),
    /** Bitwise complement. */
    COMPLEMENT("~");

    private final String symbol;

    UnaryOperation(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
