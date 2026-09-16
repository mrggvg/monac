package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code s.field} or {@code p->field}. Also an assignable place.
 *
 * <p>Both forms are kept, rather than rewriting {@code p->f} into {@code (*p).f} in
 * the parser, so that a diagnostic can say which one was written. Using the wrong
 * one is the mistake everybody makes first, and "'.' on a pointer — did you mean
 * '->'?" is a better message than anything reachable after the rewrite.
 */
public record Member(Expression base, String field, boolean throughPointer, Span span)
        implements Expression {

    /** How this access was written, for diagnostics. */
    public String operator() {
        return throughPointer ? "->" : ".";
    }
}
