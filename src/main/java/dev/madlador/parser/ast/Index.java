package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** {@code a[i]}, which means exactly {@code *(a + i)}. Also an assignable place. */
public record Index(Expression base, Expression index, Span span) implements Expression {
}
