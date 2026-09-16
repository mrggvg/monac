package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** Leaves the nearest enclosing loop. */
public record Break(Span span) implements Statement {
}
