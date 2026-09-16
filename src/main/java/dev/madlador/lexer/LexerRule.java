package dev.madlador.lexer;

import java.util.regex.Pattern;

public record LexerRule(TokenType type, Pattern pattern) {
    public LexerRule(TokenType type, String pattern) {
        this(type, Pattern.compile(pattern));
    }
}
