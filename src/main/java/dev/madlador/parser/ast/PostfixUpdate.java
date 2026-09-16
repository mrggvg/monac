package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code x++} or {@code x--}: increment the place, but produce its <em>old</em> value.
 *
 * <p>The prefix forms need no node of their own. {@code ++x} is exactly
 * {@code x = x + 1}, and an assignment already evaluates to what it stored, so the
 * parser desugars it into an {@link Assign} the way it already desugars {@code +=}.
 * Only the postfix forms need representing, because only they produce a value the
 * place no longer holds.
 */
public record PostfixUpdate(Expression target, boolean increment, Span span)
        implements Expression {

    /** The spelling, for diagnostics and the AST dump. */
    public String spelling() {
        return increment ? "++" : "--";
    }
}
