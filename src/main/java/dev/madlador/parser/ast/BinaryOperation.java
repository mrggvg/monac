package dev.madlador.parser.ast;

/** A binary operator. */
public enum BinaryOperation {
    ADD("+"), SUBTRACT("-"),
    MULTIPLY("*"), DIVIDE("/"), MODULO("%"),

    BIT_AND("&"), BIT_OR("|"), BIT_XOR("^"),
    SHIFT_LEFT("<<"), SHIFT_RIGHT(">>"),

    EQUAL("=="), NOT_EQUAL("!="),
    LESS("<"), LESS_EQUAL("<="), GREATER(">"), GREATER_EQUAL(">=");

    private final String symbol;

    BinaryOperation(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** True for the six operators that yield 0 or 1 rather than a value. */
    public boolean isComparison() {
        return ordinal() >= EQUAL.ordinal();
    }
}
