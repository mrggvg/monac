package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code goto label;} — within one function, out of blocks or within one, and never
 * forward past a declaration: the code around a label may not have been set up by a
 * jump from further in or further back.
 */
public record Goto(String label, Span span) implements Statement {
}
