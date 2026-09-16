package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * An assignment.
 *
 * <p>An <em>expression</em>, not a statement, and its target is a full expression
 * rather than a bare name. Both matter: the first gives {@code x = y = 0} and makes
 * compound assignment ordinary sugar, and the second is what {@code *p = x} and
 * {@code a[i] = x} will need. Checking that the target is actually assignable
 * becomes a semantic rule instead of a grammar restriction.
 *
 * <p>{@code compound} marks one the parser made from {@code t op= v} or {@code ++t}: its
 * value reads the target, and when the target is a computed place — {@code a[f()]},
 * {@code *p++} — that place has to be worked out once, for the read and the write
 * alike, or its side effects happen twice. Lowering reads the flag for that; the
 * analyzer reads it because C converts a compound result back to the target's type
 * without comment.
 */
public record Assign(Expression target, Expression value, Span span, boolean compound)
        implements Expression {

    /** A plain {@code target = value}. */
    public Assign(Expression target, Expression value, Span span) {
        this(target, value, span, false);
    }
}
