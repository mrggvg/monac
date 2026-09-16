package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** An integer literal, already decoded to its value. */
public record Constant(int value, Span span) implements Expression {
}
