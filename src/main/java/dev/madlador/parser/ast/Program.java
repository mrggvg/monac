package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** A whole translation unit: functions and globals, in source order. */
public record Program(List<TopLevel> items, Span span) implements Node {
}
