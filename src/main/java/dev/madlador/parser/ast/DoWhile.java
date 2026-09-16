package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code do statement while (condition);} — a loop whose body always runs once.
 *
 * <p>The only difference from {@link While} is where the test sits, so it lowers to the
 * same three blocks with the entry pointed at the body instead of the head.
 */
public record DoWhile(Statement body, Expression condition, Span span) implements Statement {
}
