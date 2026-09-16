package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A binary operation. */
public record Binary(
        BinaryOperation operation,
        Expression left,
        Expression right,
        Span span
) implements Expression {
}
