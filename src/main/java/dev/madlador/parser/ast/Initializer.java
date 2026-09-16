package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * One item of an initializer list: a value, or a list of its own for a nested
 * aggregate. Not an {@link Expression}, in C or here — a list can only follow the
 * {@code =} of a declaration — so it stays out of every switch over expressions.
 */
public sealed interface Initializer permits Initializer.Value, InitializerList {

    Span span();

    /** A single value. */
    record Value(Expression expression) implements Initializer {
        @Override
        public Span span() {
            return expression.span();
        }
    }
}
