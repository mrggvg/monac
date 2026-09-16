package dev.madlador.lexer;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexerTest {

    private DiagnosticReporter reporter;

    private List<Token> lex(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        reporter = new DiagnosticReporter(file);
        return new Lexer(file, reporter).tokenize();
    }

    /** Token types excluding the trailing EOF. */
    private List<TokenType> types(String source) {
        List<TokenType> result = new ArrayList<>();
        for (Token t : lex(source)) {
            if (!t.is(TokenType.EOF)) result.add(t.type());
        }
        return result;
    }

    @Nested
    @DisplayName("keywords")
    class KeywordTests {

        @Test
        @DisplayName("an identifier that starts with a keyword is one identifier")
        void keywordPrefixIsNotSplit() {
            // The old lexer tried keyword patterns before the identifier pattern and
            // split this into VOID followed by IDENTIFIER("ness").
            assertEquals(List.of(TokenType.IDENTIFIER), types("voidness"));
            assertEquals(List.of(TokenType.IDENTIFIER), types("returnValue"));
            assertEquals(List.of(TokenType.IDENTIFIER), types("byte_count"));
            assertEquals(List.of(TokenType.IDENTIFIER), types("iffy"));
            assertEquals(List.of(TokenType.IDENTIFIER), types("word2"));
        }

        @ParameterizedTest
        @CsvSource({
                "void,VOID", "byte,BYTE", "word,WORD", "sbyte,SBYTE", "sword,SWORD",
                "return,RETURN", "if,IF", "else,ELSE", "while,WHILE", "for,FOR",
                "break,BREAK", "continue,CONTINUE"
        })
        @DisplayName("each keyword lexes as itself")
        void keywordsRecognised(String text, TokenType expected) {
            assertEquals(List.of(expected), types(text));
        }
    }

    @Nested
    @DisplayName("operator ordering")
    class OperatorOrderingTests {

        /**
         * The rule table is ordered, so any operator that is a prefix of a longer one
         * must come after it. This is the guard for that discipline.
         */
        @ParameterizedTest
        @CsvSource({
                "'<<=',SHL_ASSIGN", "'>>=',SHR_ASSIGN",
                "'<<',SHIFT_LEFT", "'>>',SHIFT_RIGHT",
                "'<=',LESS_EQUAL", "'>=',GREATER_EQUAL",
                "'==',EQUAL", "'!=',NOT_EQUAL",
                "'&&',AND_AND", "'||',OR_OR",
                "'+=',PLUS_ASSIGN", "'-=',MINUS_ASSIGN", "'*=',STAR_ASSIGN",
                "'/=',SLASH_ASSIGN", "'%=',PERCENT_ASSIGN",
                "'&=',AMP_ASSIGN", "'|=',PIPE_ASSIGN", "'^=',CARET_ASSIGN",
                "'<',LESS", "'>',GREATER", "'=',ASSIGN", "'!',BANG",
                "'&',AMPERSAND", "'|',PIPE", "'^',CARET", "'~',TILDE",
                "'+',PLUS", "'-',DASH", "'*',STAR", "'/',SLASH", "'%',PERCENT",
                "'[',LBRACKET", "']',RBRACKET"
        })
        @DisplayName("multi-character operators lex as a single token")
        void operatorsAreMaximalMunch(String text, TokenType expected) {
            assertEquals(List.of(expected), types(text),
                    () -> "'" + text + "' must lex as one " + expected);
            assertFalse(reporter.hasErrors());
        }

        @Test
        @DisplayName("adjacent operators still separate correctly")
        void adjacentOperators() {
            assertEquals(List.of(TokenType.LESS, TokenType.DASH), types("<-"));
            assertEquals(List.of(TokenType.AND_AND, TokenType.AMPERSAND), types("&&&"));
            assertEquals(List.of(TokenType.EQUAL, TokenType.ASSIGN), types("==="));
        }
    }

    @Nested
    @DisplayName("comments")
    class CommentTests {

        @Test
        @DisplayName("line and block comments are skipped")
        void commentsSkipped() {
            assertEquals(List.of(TokenType.CONSTANT), types("// a comment\n1"));
            assertEquals(List.of(TokenType.CONSTANT), types("/* block */ 1"));
            assertEquals(List.of(TokenType.CONSTANT), types("/* multi\nline */1"));
            assertEquals(List.of(), types("// only a comment"));
        }

        @Test
        @DisplayName("comment rules do not shadow division")
        void divisionStillWorks() {
            assertEquals(List.of(TokenType.CONSTANT, TokenType.SLASH, TokenType.CONSTANT),
                    types("1/2"));
            assertEquals(List.of(TokenType.CONSTANT, TokenType.SLASH_ASSIGN, TokenType.CONSTANT),
                    types("1/=2"));
        }

        @Test
        @DisplayName("an unterminated block comment is reported")
        void unterminatedBlockComment() {
            lex("/* never closed");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("unterminated block comment"), reporter.render());
        }
    }

    @Nested
    @DisplayName("literals")
    class LiteralTests {

        @ParameterizedTest
        @CsvSource({
                "0,0", "7,7", "65535,65535",
                "0x0,0", "0xFF,255", "0xffff,65535", "0X10,16",
                "0b0,0", "0b1011,11", "0B11111111,255",
                "00,0", "017,15", "0377,255",
                "10u,10", "10U,10", "10l,10", "10LL,10", "0xFFul,255", "7LLU,7"
        })
        @DisplayName("numbers decode to their value")
        void numbersDecode(String text, int expected) {
            List<Token> tokens = lex(text);
            assertEquals(TokenType.CONSTANT, tokens.get(0).type());
            assertEquals(expected, tokens.get(0).value());
            assertFalse(reporter.hasErrors(), reporter.render());
        }

        @Test
        @DisplayName("a ' between digits is a separator, as C23 has it")
        void digitSeparators() {
            for (String[] each : new String[][]{{"1'000", "1000"}, {"0x1'00", "256"},
                                                {"0b1010'1010", "170"}, {"0'17", "15"}}) {
                List<Token> tokens = lex(each[0]);
                assertEquals(TokenType.CONSTANT, tokens.get(0).type(), reporter.render());
                assertEquals(Integer.parseInt(each[1]), tokens.get(0).value(), each[0]);
                assertFalse(reporter.hasErrors(), reporter.render());
            }
        }

        @Test
        @DisplayName("a digit above seven in an octal constant is reported")
        void octalOutOfRange() {
            lex("09");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("invalid digit '9' in octal"), reporter.render());
        }

        @Test
        @DisplayName("the rest of C's escapes, including octal and long \\x")
        void cEscapes() {
            for (String[] each : new String[][]{{"'\\101'", "65"}, {"'\\0'", "0"}, {"'\\7'", "7"},
                                                {"'\\x041'", "65"}, {"'\\a'", "7"}, {"'\\f'", "12"},
                                                {"'\\v'", "11"}, {"'\\?'", "63"}}) {
                List<Token> tokens = lex(each[0]);
                assertEquals(TokenType.CHAR_LITERAL, tokens.get(0).type(), reporter.render());
                assertEquals(Integer.parseInt(each[1]), tokens.get(0).value(), each[0]);
                assertFalse(reporter.hasErrors(), reporter.render());
            }
        }

        @Test
        @DisplayName("an escape that does not fit in a byte is reported")
        void escapeOutOfRange() {
            lex("'\\x1FF'");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("out of range"), reporter.render());
        }

        @Test
        @DisplayName("a number too large for 16 bits is reported")
        void numberOutOfRange() {
            lex("65536");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("does not fit in 16 bits"), reporter.render());
        }

        @Test
        @DisplayName("a digit run glued to letters is a malformed number, not two tokens")
        void malformedNumber() {
            lex("123abc");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("malformed number"), reporter.render());
        }

        @ParameterizedTest
        @CsvSource({
                "'''a''',97", "'''Z''',90", "'''0''',48", "'''\\n''',10",
                "'''\\t''',9", "'''\\0''',0", "'''\\\\''',92", "'''\\x41''',65"
        })
        @DisplayName("character literals decode, including escapes")
        void charLiterals(String text, int expected) {
            List<Token> tokens = lex(text);
            assertEquals(TokenType.CHAR_LITERAL, tokens.get(0).type(), reporter.render());
            assertEquals(expected, tokens.get(0).value(), reporter.render());
            assertFalse(reporter.hasErrors(), reporter.render());
        }

        @Test
        @DisplayName("string literals decode escapes into the lexeme")
        void stringLiterals() {
            List<Token> tokens = lex("\"hi\\n\"");
            assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
            assertEquals("hi\n", tokens.get(0).lexeme());

            tokens = lex("\"\"");
            assertEquals(TokenType.STRING_LITERAL, tokens.get(0).type());
            assertEquals("", tokens.get(0).lexeme());
        }
    }

    @Nested
    @DisplayName("error handling")
    class ErrorTests {

        @Test
        @DisplayName("an unexpected character is reported and skipped, not fatal")
        void recoversFromBadCharacter() {
            // The old lexer printed to stdout and returned a truncated token list.
            List<TokenType> t = types("1 @ 2");
            assertEquals(List.of(TokenType.CONSTANT, TokenType.CONSTANT), t,
                    "lexing must continue past the bad character");
            assertTrue(reporter.hasErrors());
            assertTrue(reporter.render().contains("unexpected character '@'"), reporter.render());
        }

        @Test
        @DisplayName("every error carries a line and column")
        void errorsArePositioned() {
            lex("word x;\nword y @;\n");
            assertTrue(reporter.render().contains("test.mona:2:8"), reporter.render());
        }
    }

    @Nested
    @DisplayName("spans")
    class SpanTests {

        @Test
        @DisplayName("token spans cover exactly the source text")
        void spansAreExact() {
            String src = "word count = 42;";
            SourceFile file = new SourceFile("t.mona", src);
            List<Token> tokens = new Lexer(file, new DiagnosticReporter(file)).tokenize();

            assertEquals("word", file.textOf(tokens.get(0).span()));
            assertEquals("count", file.textOf(tokens.get(1).span()));
            assertEquals("=", file.textOf(tokens.get(2).span()));
            assertEquals("42", file.textOf(tokens.get(3).span()));
            assertEquals(";", file.textOf(tokens.get(4).span()));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "// nothing\n", "/* nothing */"})
        @DisplayName("an empty or comment-only file yields just EOF")
        void emptyInput(String source) {
            List<Token> tokens = lex(source);
            assertEquals(1, tokens.size());
            assertEquals(TokenType.EOF, tokens.get(0).type());
        }
    }

    @Test
    @DisplayName("a realistic program lexes without errors")
    void realisticProgram() {
        List<Token> tokens = lex("""
                // greatest common divisor
                word gcd(word a, word b) {
                    while (b != 0) {
                        word t = b;
                        b = a % b;
                        a = t;
                    }
                    return a;   /* result in a */
                }
                """);
        assertFalse(reporter.hasErrors(), reporter.render());
        assertEquals(TokenType.EOF, tokens.get(tokens.size() - 1).type());
        assertTrue(tokens.size() > 30);
    }
}
