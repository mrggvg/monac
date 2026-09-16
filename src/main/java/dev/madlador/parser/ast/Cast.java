package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

/**
 * {@code (byte)value}: an explicit conversion.
 *
 * <p>Which is a cast and which is a parenthesised expression is decided by one token.
 * Every type in this language begins with a keyword — {@code struct} included — and no
 * expression does, so {@code (} followed by a type keyword can only be a cast. That is
 * the same property {@link SizeOf} relies on, and it survives only because the language
 * has no {@code typedef}: a type name that were an ordinary identifier would make
 * {@code (Foo)x} and {@code (foo)} indistinguishable without a symbol table.
 */
public record Cast(TypeRef type, Expression operand, Span span) implements Expression {
}
