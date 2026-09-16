package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/** One name in an enum, and its value when the source writes one. */
public record Enumerator(String name, Optional<Expression> value, Span span) {
}
