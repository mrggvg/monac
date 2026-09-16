package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** {@code label: statement} — somewhere a {@link Goto} in the same function can go. */
public record Labeled(String label, Statement body, Span span) implements Statement {
}
