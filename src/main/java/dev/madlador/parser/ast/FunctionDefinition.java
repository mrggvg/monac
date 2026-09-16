package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/** A function with its signature and body. */
public record FunctionDefinition(
        TypeRef returnType,
        String name,
        List<Parameter> parameters,
        Block body,
        Span span
) implements TopLevel {
}
