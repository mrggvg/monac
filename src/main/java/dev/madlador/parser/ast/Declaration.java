package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/**
 * A local variable declaration, with an optional initializer: an expression, or a
 * braced list for an aggregate — never both.
 *
 * <p>{@code isStatic} makes it a global that only its block can name: one copy for
 * every call, initialised in the image rather than each time the line is reached.
 */
public record Declaration(
        TypeRef type,
        String name,
        Optional<Expression> initializer,
        Optional<InitializerList> list,
        boolean isStatic,
        Span span
) implements BlockItem {
}
