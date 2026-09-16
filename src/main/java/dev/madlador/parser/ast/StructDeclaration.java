package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * {@code struct Name { members };} at file scope, or {@code union Name { ... };},
 * whose members all start at offset 0.
 *
 * <p>A member is a {@link Parameter}: a type and a name, which is exactly what a
 * member is. Reusing it keeps one shape rather than two identical ones.
 */
public record StructDeclaration(String name, List<Parameter> members, boolean isUnion,
                                Span span)
        implements TopLevel {
}
