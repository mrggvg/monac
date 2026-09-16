package dev.madlador.lexer;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.diag.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Turns source text into tokens.
 *
 * <p>Keeps the ordered rule-table design, with three fixes over the original:
 * matching runs against a {@link Matcher#region region} of the input rather than a
 * fresh {@code substring} per attempt (the old version was quadratic in file size);
 * keywords are recognised by looking up a completed identifier rather than by
 * pattern rules placed before it (so {@code voidness} is one identifier, not
 * {@code void} followed by {@code ness}); and an unrecognised character is reported
 * and skipped rather than silently truncating the token stream.
 *
 * <p><b>Rule order is load-bearing.</b> An earlier rule wins, so every operator must
 * be listed before any operator that is a prefix of it — {@code <<=} before
 * {@code <<} before {@code <=} before {@code <} — and the comment rules must precede
 * {@code /=} and {@code /}. {@code LexerOrderingTest} guards this.
 */
public class Lexer {

    private final SourceFile source;
    private final String input;
    private final DiagnosticReporter reporter;
    private int cursor = 0;

    /** Marks a rule whose match produces no token. */
    private static final TokenType SKIP = TokenType.WS;

    /**
     * C's integer suffixes, in any order C allows: {@code u}, {@code l}, {@code ll},
     * either case, and an unsigned one on either side. Every integer here is 16 bits,
     * so they change nothing, but a program written for C should not stop at them.
     */
    private static final String SUFFIX = "(?:[uU](?:ll|LL|[lL])?|(?:ll|LL|[lL])[uU]?)?";

    private final List<LexerRule> rules = List.of(
            // --- trivia: comments must precede the operators they start with ---
            new LexerRule(SKIP, "\\s+"),
            new LexerRule(SKIP, "//[^\\r\\n]*"),
            new LexerRule(SKIP, "/\\*[\\s\\S]*?\\*/"),

            // --- identifiers and keywords (keyword lookup happens after matching) ---
            new LexerRule(TokenType.IDENTIFIER, "[a-zA-Z_][a-zA-Z0-9_]*"),

            // --- numbers: prefixed forms before plain decimal, which also takes
            // octal. A ' between two digits is a C23 separator, so it has to be matched
            // here, before the character literal it would otherwise start ---
            new LexerRule(TokenType.CONSTANT, "0[xX][0-9a-fA-F](?:'?[0-9a-fA-F])*" + SUFFIX),
            new LexerRule(TokenType.CONSTANT, "0[bB][01](?:'?[01])*" + SUFFIX),
            new LexerRule(TokenType.CONSTANT, "[0-9](?:'?[0-9])*" + SUFFIX),

            // --- quoted literals ---
            // \x and octal escapes must be listed before the general \. escape, which
            // would otherwise consume only the backslash and one character.
            new LexerRule(TokenType.CHAR_LITERAL,
                    "'(?:\\\\x[0-9a-fA-F]+|\\\\[0-7]{1,3}|\\\\.|[^'\\\\])'"),
            new LexerRule(TokenType.STRING_LITERAL, "\"(?:\\\\.|[^\"\\\\])*\""),

            // --- three-character operators ---
            new LexerRule(TokenType.SHL_ASSIGN, "<<="),
            new LexerRule(TokenType.SHR_ASSIGN, ">>="),

            // --- two-character operators ---
            new LexerRule(TokenType.SHIFT_LEFT, "<<"),
            new LexerRule(TokenType.SHIFT_RIGHT, ">>"),
            new LexerRule(TokenType.LESS_EQUAL, "<="),
            new LexerRule(TokenType.GREATER_EQUAL, ">="),
            new LexerRule(TokenType.EQUAL, "=="),
            new LexerRule(TokenType.NOT_EQUAL, "!="),
            new LexerRule(TokenType.AND_AND, "&&"),
            new LexerRule(TokenType.OR_OR, "\\|\\|"),
            new LexerRule(TokenType.PLUS_ASSIGN, "\\+="),
            new LexerRule(TokenType.PLUS_PLUS, "\\+\\+"),
            new LexerRule(TokenType.MINUS_MINUS, "--"),
            new LexerRule(TokenType.ARROW, "->"),
            new LexerRule(TokenType.MINUS_ASSIGN, "-="),
            new LexerRule(TokenType.STAR_ASSIGN, "\\*="),
            new LexerRule(TokenType.SLASH_ASSIGN, "/="),
            new LexerRule(TokenType.PERCENT_ASSIGN, "%="),
            new LexerRule(TokenType.AMP_ASSIGN, "&="),
            new LexerRule(TokenType.PIPE_ASSIGN, "\\|="),
            new LexerRule(TokenType.CARET_ASSIGN, "\\^="),

            // --- single-character operators ---
            new LexerRule(TokenType.STAR, "\\*"),
            new LexerRule(TokenType.SLASH, "/"),
            new LexerRule(TokenType.PERCENT, "%"),
            new LexerRule(TokenType.PLUS, "\\+"),
            new LexerRule(TokenType.DASH, "-"),
            new LexerRule(TokenType.AMPERSAND, "&"),
            new LexerRule(TokenType.PIPE, "\\|"),
            new LexerRule(TokenType.CARET, "\\^"),
            new LexerRule(TokenType.TILDE, "~"),
            new LexerRule(TokenType.BANG, "!"),
            new LexerRule(TokenType.LESS, "<"),
            new LexerRule(TokenType.GREATER, ">"),
            new LexerRule(TokenType.ASSIGN, "="),
            new LexerRule(TokenType.QUESTION, "\\?"),

            // --- delimiters ---
            new LexerRule(TokenType.LPAREN, "\\("),
            new LexerRule(TokenType.RPAREN, "\\)"),
            new LexerRule(TokenType.LBRACE, "\\{"),
            new LexerRule(TokenType.RBRACE, "\\}"),
            new LexerRule(TokenType.DOT, "\\."),
            new LexerRule(TokenType.LBRACKET, "\\["),
            new LexerRule(TokenType.RBRACKET, "\\]"),
            new LexerRule(TokenType.COMMA, ","),
            new LexerRule(TokenType.SEMI, ";"),
            new LexerRule(TokenType.COLON, ":")
    );

    private final List<Matcher> matchers;

    public Lexer(SourceFile source, DiagnosticReporter reporter) {
        this.source = source;
        this.input = source.text();
        this.reporter = reporter;
        this.matchers = new ArrayList<>(rules.size());
        // One reusable Matcher per rule; region() then avoids reallocating the input.
        for (LexerRule rule : rules) {
            Matcher m = rule.pattern().matcher(input);
            m.useAnchoringBounds(false);
            m.useTransparentBounds(false);
            matchers.add(m);
        }
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();

        while (cursor < input.length()) {
            int matchedLength = -1;
            LexerRule matchedRule = null;

            for (int i = 0; i < rules.size(); i++) {
                Matcher m = matchers.get(i);
                m.region(cursor, input.length());
                if (m.lookingAt()) {
                    matchedLength = m.end() - cursor;
                    matchedRule = rules.get(i);
                    break;
                }
            }

            if (matchedRule == null) {
                // Unrecognised character: report it, skip it, and keep going so the
                // rest of the file still produces useful diagnostics.
                Span span = new Span(cursor, cursor + 1);
                reporter.error(span, "unexpected character " + describeChar(input.charAt(cursor)));
                cursor++;
                continue;
            }

            if (matchedLength == 0) {
                // A rule that can match empty would spin forever; treat as unexpected.
                Span span = new Span(cursor, cursor + 1);
                reporter.error(span, "unexpected character " + describeChar(input.charAt(cursor)));
                cursor++;
                continue;
            }

            Span span = new Span(cursor, cursor + matchedLength);
            String text = input.substring(cursor, cursor + matchedLength);
            cursor += matchedLength;

            if (matchedRule.type() == SKIP) {
                continue;
            }
            Token token = makeToken(matchedRule.type(), text, span);
            if (token != null) tokens.add(token);
        }

        checkUnterminated();
        tokens.add(new Token(TokenType.EOF, "", new Span(input.length(), input.length())));
        return tokens;
    }

    /** Reports an unterminated block comment or quoted literal at end of input. */
    private void checkUnterminated() {
        // The comment/string rules simply fail to match when unterminated, so the
        // opening character is reported as unexpected. Add the more useful message.
        int i = input.lastIndexOf("/*");
        if (i >= 0 && input.indexOf("*/", i) < 0) {
            reporter.error(new Span(i, Math.min(i + 2, input.length())), "unterminated block comment");
        }
    }

    private Token makeToken(TokenType type, String text, Span span) {
        return switch (type) {
            case IDENTIFIER -> new Token(Keywords.lookup(text), text, span);
            case CONSTANT -> numberToken(text, span);
            case CHAR_LITERAL -> charToken(text, span);
            case STRING_LITERAL -> stringToken(text, span);
            default -> new Token(type, text, span);
        };
    }

    private Token numberToken(String text, Span span) {
        // A digit run immediately followed by an identifier character is a typo, not
        // two tokens: catch it here rather than letting the parser report something
        // baffling about an unexpected identifier.
        if (cursor < input.length() && isIdentifierChar(input.charAt(cursor))) {
            int end = cursor;
            while (end < input.length() && isIdentifierChar(input.charAt(end))) end++;
            Span bad = new Span(span.start(), end);
            reporter.error(bad, "malformed number '" + input.substring(span.start(), end) + "'");
            cursor = end;
            return new Token(TokenType.CONSTANT, text, 0, bad);
        }

        // The separators and the suffix only matter to the reader.
        String digits = text.replace("'", "");
        int end = digits.length();
        while (end > 0 && "uUlL".indexOf(digits.charAt(end - 1)) >= 0) end--;
        digits = digits.substring(0, end);

        long value;
        try {
            if (digits.length() > 2 && (digits.charAt(1) == 'x' || digits.charAt(1) == 'X')) {
                value = Long.parseLong(digits.substring(2), 16);
            } else if (digits.length() > 2 && (digits.charAt(1) == 'b' || digits.charAt(1) == 'B')) {
                value = Long.parseLong(digits.substring(2), 2);
            } else if (digits.length() > 1 && digits.charAt(0) == '0') {
                // A leading zero means octal, as in C: 017 is fifteen, not seventeen.
                for (char c : digits.toCharArray()) {
                    if (c > '7') {
                        reporter.error(span, "invalid digit '" + c + "' in octal constant '" + text + "'",
                                "a leading 0 makes a number octal, as in C; leave it off for decimal");
                        return new Token(TokenType.CONSTANT, text, 0, span);
                    }
                }
                value = Long.parseLong(digits, 8);
            } else {
                value = Long.parseLong(digits, 10);
            }
        } catch (NumberFormatException e) {
            reporter.error(span, "number '" + text + "' does not fit in 16 bits");
            return new Token(TokenType.CONSTANT, text, 0, span);
        }

        // Range-check here rather than letting the assembler reject it much later
        // with a message that means nothing to a Mona programmer.
        if (value > 0xFFFF) {
            reporter.error(span, "number '" + text + "' does not fit in 16 bits",
                    "the largest value a word can hold is 65535 (0xFFFF)");
            value &= 0xFFFF;
        }
        return new Token(TokenType.CONSTANT, text, (int) value, span);
    }

    private Token charToken(String text, Span span) {
        String body = text.substring(1, text.length() - 1);
        StringBuilder decoded = new StringBuilder();
        decodeEscapes(body, span, decoded);
        if (decoded.length() != 1) {
            reporter.error(span, "character literal must contain exactly one character");
            return new Token(TokenType.CHAR_LITERAL, text, 0, span);
        }
        int value = decoded.charAt(0);
        if (value > 0xFF) {
            reporter.error(span, "character literal does not fit in a byte");
            value &= 0xFF;
        }
        return new Token(TokenType.CHAR_LITERAL, text, value, span);
    }

    private Token stringToken(String text, Span span) {
        String body = text.substring(1, text.length() - 1);
        StringBuilder decoded = new StringBuilder();
        decodeEscapes(body, span, decoded);
        return new Token(TokenType.STRING_LITERAL, decoded.toString(), 0, span);
    }

    private void decodeEscapes(String body, Span span, StringBuilder out) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (++i >= body.length()) {
                reporter.error(span, "trailing backslash in literal");
                return;
            }
            char e = body.charAt(i);
            switch (e) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'a' -> out.append((char) 7);
                case 'f' -> out.append('\f');
                case 'v' -> out.append((char) 11);
                case '\\' -> out.append('\\');
                case '\'' -> out.append('\'');
                case '"' -> out.append('"');
                case '?' -> out.append('?');
                // An octal byte of one to three digits, as in C, which makes \0 the
                // one-digit case of it: "\101" is "A".
                case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                    int start = i;
                    int end = start;
                    while (end < body.length() && end < start + 3 && body.charAt(end) >= '0'
                            && body.charAt(end) <= '7') end++;
                    appendByte(Integer.parseInt(body.substring(start, end), 8), span, out);
                    i = end - 1;
                }
                // As in C, \x takes every hexadecimal digit that follows it.
                case 'x' -> {
                    int start = i + 1;
                    int end = start;
                    while (end < body.length() && isHexDigit(body.charAt(end))) end++;
                    if (end == start) {
                        reporter.error(span, "\\x needs at least one hexadecimal digit");
                    } else if (end - start > 4) {
                        reporter.error(span, "\\x escape out of range: a byte is at most \\xFF");
                        i = end - 1;
                    } else {
                        appendByte(Integer.parseInt(body.substring(start, end), 16), span, out);
                        i = end - 1;
                    }
                }
                default -> reporter.error(span, "unknown escape sequence '\\" + e + "'");
            }
        }
    }

    /** One byte of an escape, which a value above 255 cannot be. */
    private void appendByte(int value, Span span, StringBuilder out) {
        if (value > 0xFF) {
            reporter.error(span, "escape sequence out of range: a byte is at most 255");
            value &= 0xFF;
        }
        out.append((char) value);
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String describeChar(char c) {
        if (c >= 0x20 && c < 0x7F) return "'" + c + "'";
        return String.format("'\\x%02X'", (int) c);
    }

    public SourceFile source() {
        return source;
    }
}
