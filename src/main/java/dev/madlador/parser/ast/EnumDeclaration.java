package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * {@code enum Name { A, B = 5, C };}, at file scope or inside a block.
 *
 * <p>Each enumerator is a named word, one more than the one before unless it says
 * otherwise, as in C. The tag is optional; when present it names a type that is simply
 * a word, so {@code enum Color c;} documents intent and costs nothing.
 *
 * @param name the tag, or null for an anonymous enum
 */
public record EnumDeclaration(String name, List<Enumerator> members, Span span)
        implements TopLevel, BlockItem {
}
