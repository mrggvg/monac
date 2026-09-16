package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** {@code { a, { b, c }, "text" }}: values in order, nested for nested aggregates. */
public record InitializerList(List<Initializer> items, Span span) implements Initializer {
}
