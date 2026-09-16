package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A reference to a named variable. */
public record Identifier(String name, Span span) implements Expression {
}
