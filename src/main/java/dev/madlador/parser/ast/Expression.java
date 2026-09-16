package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** An expression: a computation that produces a value. */
public sealed interface Expression extends Node
        permits Binary, Logical, Unary, Assign, Call, Constant, Identifier,
                AddressOf, Deref, Index, Member, SizeOf, StringLiteral,
                PostfixUpdate, Cast, Conditional, Comma, IndirectCall {
}
