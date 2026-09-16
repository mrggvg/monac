package dev.madlador.lexer;

import java.util.Map;

/**
 * Reserved words, looked up <em>after</em> an identifier has been scanned.
 *
 * <p>This ordering is what makes {@code voidness} a single identifier. The previous
 * lexer tried keyword patterns before the identifier pattern, so it split that into
 * {@code void} followed by {@code ness}. Matching the longest identifier first and
 * then consulting this map removes the bug and the rule-ordering fragility together.
 */
public final class Keywords {

    private static final Map<String, TokenType> TABLE = Map.ofEntries(
            Map.entry("void", TokenType.VOID),
            Map.entry("byte", TokenType.BYTE),
            Map.entry("word", TokenType.WORD),
            Map.entry("sbyte", TokenType.SBYTE),
            Map.entry("sword", TokenType.SWORD),
            Map.entry("return", TokenType.RETURN),
            Map.entry("if", TokenType.IF),
            Map.entry("else", TokenType.ELSE),
            Map.entry("while", TokenType.WHILE),
            Map.entry("do", TokenType.DO),
            Map.entry("for", TokenType.FOR),
            Map.entry("break", TokenType.BREAK),
            Map.entry("continue", TokenType.CONTINUE),
            Map.entry("goto", TokenType.GOTO),
            Map.entry("switch", TokenType.SWITCH),
            Map.entry("case", TokenType.CASE),
            Map.entry("default", TokenType.DEFAULT),
            Map.entry("struct", TokenType.STRUCT),
            Map.entry("union", TokenType.UNION),
            Map.entry("sizeof", TokenType.SIZEOF),
            Map.entry("enum", TokenType.ENUM),
            Map.entry("const", TokenType.CONST),
            Map.entry("static", TokenType.STATIC)
    );

    private Keywords() {
    }

    /** The keyword type for {@code text}, or {@code IDENTIFIER} if it is not reserved. */
    public static TokenType lookup(String text) {
        return TABLE.getOrDefault(text, TokenType.IDENTIFIER);
    }

    public static boolean isKeyword(String text) {
        return TABLE.containsKey(text);
    }
}
