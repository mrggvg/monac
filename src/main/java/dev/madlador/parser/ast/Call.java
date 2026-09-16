package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** A function call. */
public record Call(String callee, List<Expression> arguments, Span span)
        implements Expression {
}
