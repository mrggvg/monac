package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A prefix operation. */
public record Unary(UnaryOperation operation, Expression operand, Span span)
        implements Expression {
}
