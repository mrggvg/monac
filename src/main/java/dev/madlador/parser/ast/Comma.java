package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code a, b} — evaluate the left, discard it, produce the right.
 *
 * <p>It binds looser than assignment, which is what makes {@code f(a, b)} two arguments
 * rather than one comma expression: an argument is parsed at assignment level, so the
 * comma between arguments is never reached as an operator. The same goes for a case
 * label and an initializer.
 */
public record Comma(Expression left, Expression right, Span span) implements Expression {
}
