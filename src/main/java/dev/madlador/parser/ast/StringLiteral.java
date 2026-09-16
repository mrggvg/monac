package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * A string literal.
 *
 * <p>Becomes a NUL-terminated run of bytes after the code, and the expression itself
 * is the address of the first byte — so its type is {@code byte*}.
 */
public record StringLiteral(String value, Span span) implements Expression {
}
