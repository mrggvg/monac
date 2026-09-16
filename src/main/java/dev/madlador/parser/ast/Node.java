package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * Base of the syntax tree.
 *
 * <p>Sealed, so a {@code switch} over node types is checked for exhaustiveness at
 * compile time. That replaces the old {@code Visitor} interface: adding a node type
 * now produces errors only in the passes that actually need to handle it, instead of
 * forcing every pass to grow a method.
 */
public sealed interface Node
        permits Program, TopLevel, Parameter, BlockItem, Expression {

    /** Where this node came from, for diagnostics. */
    Span span();
}
