package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * A prototype: a function's signature with a {@code ;} where the body would be.
 *
 * <p>Mona never needed one to call a function defined further down the file — every
 * top-level name is declared before any body is analysed — but C source is full of
 * them, and a prototype its definition disagrees with is a mistake worth reporting.
 *
 * <p>A parameter's name is optional here, as in C, and null where it was left out.
 */
public record FunctionDeclaration(
        TypeRef returnType,
        String name,
        List<Parameter> parameters,
        Span span
) implements TopLevel {
}
