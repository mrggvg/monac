package dev.madlador.lexer;

public enum TokenType {

    // Type keywords. word/byte are unsigned; sword/sbyte are two's complement.
    VOID, BYTE, WORD, SBYTE, SWORD,

    // Statement keywords
    RETURN, IF, ELSE, WHILE, DO, FOR, BREAK, CONTINUE, SWITCH, CASE, DEFAULT, GOTO,
    STRUCT, UNION, SIZEOF,

    // Declaration keywords
    ENUM, CONST, STATIC,

    IDENTIFIER, CONSTANT, CHAR_LITERAL, STRING_LITERAL,

    // Arithmetic
    STAR, SLASH, PERCENT, PLUS, DASH,

    // Bitwise and shifts
    AMPERSAND, PIPE, CARET, TILDE, SHIFT_LEFT, SHIFT_RIGHT,

    // Comparison
    EQUAL, NOT_EQUAL, LESS, LESS_EQUAL, GREATER, GREATER_EQUAL,

    // Logical
    AND_AND, OR_OR, BANG,

    // Increment and decrement
    PLUS_PLUS, MINUS_MINUS,

    // Assignment
    ASSIGN,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN, PERCENT_ASSIGN,
    AMP_ASSIGN, PIPE_ASSIGN, CARET_ASSIGN, SHL_ASSIGN, SHR_ASSIGN,

    // Delimiters
    QUESTION, COLON, DOT, ARROW,
    LPAREN, RPAREN,
    LBRACE, RBRACE,
    LBRACKET, RBRACKET,
    COMMA, SEMI,

    // Helper tokens
    EOF, WS;

    /** True for the type keywords that can begin a declaration. */
    public boolean isTypeSpecifier() {
        return this == VOID || this == BYTE || this == WORD || this == SBYTE || this == SWORD
                || this == STRUCT || this == UNION || this == ENUM;
    }

    /** True for what can begin a type: a type keyword, or a {@code const} before one. */
    public boolean beginsType() {
        return isTypeSpecifier() || this == CONST;
    }

    /** True for what can begin a declaration: a type, or {@code static} before one. */
    public boolean beginsDeclaration() {
        return beginsType() || this == STATIC;
    }

    /** The source spelling, for error messages. */
    public String describe() {
        return switch (this) {
            case VOID -> "'void'";
            case BYTE -> "'byte'";
            case WORD -> "'word'";
            case SBYTE -> "'sbyte'";
            case SWORD -> "'sword'";
            case RETURN -> "'return'";
            case IF -> "'if'";
            case ELSE -> "'else'";
            case WHILE -> "'while'";
            case DO -> "'do'";
            case FOR -> "'for'";
            case BREAK -> "'break'";
            case CONTINUE -> "'continue'";
            case GOTO -> "'goto'";
            case SWITCH -> "'switch'";
            case STRUCT -> "'struct'";
            case UNION -> "'union'";
            case SIZEOF -> "'sizeof'";
            case ENUM -> "'enum'";
            case CONST -> "'const'";
            case STATIC -> "'static'";
            case DOT -> "'.'";
            case ARROW -> "'->'";
            case CASE -> "'case'";
            case DEFAULT -> "'default'";
            case IDENTIFIER -> "an identifier";
            case CONSTANT -> "a number";
            case CHAR_LITERAL -> "a character literal";
            case STRING_LITERAL -> "a string literal";
            case STAR -> "'*'";
            case SLASH -> "'/'";
            case PERCENT -> "'%'";
            case PLUS -> "'+'";
            case DASH -> "'-'";
            case AMPERSAND -> "'&'";
            case PIPE -> "'|'";
            case CARET -> "'^'";
            case TILDE -> "'~'";
            case SHIFT_LEFT -> "'<<'";
            case SHIFT_RIGHT -> "'>>'";
            case EQUAL -> "'=='";
            case NOT_EQUAL -> "'!='";
            case LESS -> "'<'";
            case LESS_EQUAL -> "'<='";
            case GREATER -> "'>'";
            case GREATER_EQUAL -> "'>='";
            case AND_AND -> "'&&'";
            case OR_OR -> "'||'";
            case BANG -> "'!'";
            case ASSIGN -> "'='";
            case PLUS_PLUS -> "'++'";
            case MINUS_MINUS -> "'--'";
            case PLUS_ASSIGN -> "'+='";
            case MINUS_ASSIGN -> "'-='";
            case STAR_ASSIGN -> "'*='";
            case SLASH_ASSIGN -> "'/='";
            case PERCENT_ASSIGN -> "'%='";
            case AMP_ASSIGN -> "'&='";
            case PIPE_ASSIGN -> "'|='";
            case CARET_ASSIGN -> "'^='";
            case SHL_ASSIGN -> "'<<='";
            case SHR_ASSIGN -> "'>>='";
            case LPAREN -> "'('";
            case RPAREN -> "')'";
            case LBRACE -> "'{'";
            case RBRACE -> "'}'";
            case LBRACKET -> "'['";
            case RBRACKET -> "']'";
            case QUESTION -> "'?'";
            case COLON -> "':'";
            case COMMA -> "','";
            case SEMI -> "';'";
            case EOF -> "end of file";
            case WS -> "whitespace";
        };
    }
}
