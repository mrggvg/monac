package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/** A return statement; the value is absent in a {@code void} function. */
public record Return(Optional<Expression> value, Span span) implements Statement {
}
