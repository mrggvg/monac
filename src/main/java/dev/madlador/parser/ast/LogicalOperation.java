package dev.madlador.parser.ast;

/**
 * A short-circuiting connective.
 *
 * <p>Separate from {@link BinaryOperation} because these do not evaluate both
 * operands, so they lower to branches rather than to an ALU instruction.
 */
public enum LogicalOperation {
    AND("&&"), OR("||");

    private final String symbol;

    LogicalOperation(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
