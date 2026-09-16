package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A lone semicolon, which is a legal statement and does nothing. */
public record Empty(Span span) implements Statement {
}
