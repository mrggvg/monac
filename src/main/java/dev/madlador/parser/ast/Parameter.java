package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** One formal parameter. The name is null only in a prototype that left it out. */
public record Parameter(TypeRef type, String name, Span span) implements Node {
}
