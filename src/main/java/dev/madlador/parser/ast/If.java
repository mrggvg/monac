package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/** A conditional, with an optional else branch. */
public record If(Expression condition, Statement thenBranch,
                 Optional<Statement> elseBranch, Span span) implements Statement {
}
