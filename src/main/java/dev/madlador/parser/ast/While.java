package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A while loop. */
public record While(Expression condition, Statement body, Span span) implements Statement {
}
