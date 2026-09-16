package dev.madlador.lexer;

import dev.madlador.diag.Span;

/**
 * One lexical token.
 *
 * @param type   what kind of token it is
 * @param lexeme the exact source text, except for literals where it is the decoded value
 * @param value  the numeric value of a CONSTANT or CHAR_LITERAL, otherwise 0
 * @param span   where the token came from, used for every diagnostic downstream
 */
public record Token(TokenType type, String lexeme, int value, Span span) {

    public Token(TokenType type, String lexeme, Span span) {
        this(type, lexeme, 0, span);
    }

    public boolean is(TokenType other) {
        return type == other;
    }

    @Override
    public String toString() {
        return switch (type) {
            case CONSTANT, CHAR_LITERAL -> type + "(" + value + ")";
            case IDENTIFIER, STRING_LITERAL -> type + "(" + lexeme + ")";
            case EOF -> "EOF";
            default -> type.toString();
        };
    }
}
