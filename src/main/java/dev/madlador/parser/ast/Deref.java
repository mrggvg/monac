package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** {@code *p}: the object a pointer refers to. Also an assignable place. */
public record Deref(Expression operand, Span span) implements Expression {
}
