package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** Anything that may appear directly inside a {@link Block}. */
public sealed interface BlockItem extends Node permits Declaration, Statement, EnumDeclaration {
}
