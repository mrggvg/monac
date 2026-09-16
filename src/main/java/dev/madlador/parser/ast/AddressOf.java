package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** {@code &x}: the address of an object. */
public record AddressOf(Expression operand, Span span) implements Expression {
}
