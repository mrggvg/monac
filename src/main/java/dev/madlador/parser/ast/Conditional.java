package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code cond ? a : b}.
 *
 * <p>Exactly an {@code if}/{@code else} that produces a value, and lowered as one —
 * only the chosen arm is evaluated, so {@code p != 0 ? *p : 0} is safe in the way the
 * short-circuit operators are.
 */
public record Conditional(Expression condition, Expression then, Expression otherwise,
                          Span span) implements Expression {
}
