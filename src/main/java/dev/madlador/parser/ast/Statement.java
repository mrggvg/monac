package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** A statement. */
public sealed interface Statement extends BlockItem
        permits Block, Return, ExpressionStatement, If, While, DoWhile, For, Break,
                Continue, Empty, Switch, Goto, Labeled {
}
