package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/** Something that may appear at file scope. */
public sealed interface TopLevel extends Node permits FunctionDefinition, FunctionDeclaration,
        GlobalDeclaration, StructDeclaration, EnumDeclaration {
}
