package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * One arm of a switch.
 *
 * <p>{@code label} is null for {@code default}. Its value is worked out in analysis
 * rather than here, because a label may name a constant — {@code case RED:} — that
 * the parser has no way to look up. The body is a list of block items rather than a
 * block, which is what gives C's fall-through for free: control simply runs on into
 * the next arm unless something breaks out.
 */
public record SwitchCase(Expression label, List<BlockItem> body, Span span) {

    public boolean isDefault() {
        return label == null;
    }
}
