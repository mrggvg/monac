package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** Starts the next iteration of the nearest enclosing loop. */
public record Continue(Span span) implements Statement {
}
