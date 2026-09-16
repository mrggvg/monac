package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** A switch statement. */
public record Switch(Expression subject, List<SwitchCase> cases, Span span)
        implements Statement {
}
