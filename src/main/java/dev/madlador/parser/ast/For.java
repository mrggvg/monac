package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/**
 * A for loop.
 *
 * <p>The initializer may be a declaration, so the loop introduces a scope of its own
 * and {@code for (word i = 0; ...)} keeps {@code i} out of the enclosing block.
 */
public record For(Optional<BlockItem> initializer,
                  Optional<Expression> condition,
                  Optional<Expression> update,
                  Statement body,
                  Span span) implements Statement {
}
