package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** A brace-delimited sequence of declarations and statements. */
public record Block(List<BlockItem> items, Span span) implements Statement {
}
