package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A short-circuiting {@code &&} or {@code ||}. */
public record Logical(LogicalOperation operation, Expression left, Expression right, Span span)
        implements Expression {
}
