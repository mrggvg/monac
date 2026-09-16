package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** An expression evaluated for its effect, such as an assignment or a call. */
public record ExpressionStatement(Expression expression, Span span) implements Statement {
}
