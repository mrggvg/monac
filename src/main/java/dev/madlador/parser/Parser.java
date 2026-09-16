package dev.madlador.parser;

import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.Span;
import dev.madlador.lexer.Token;
import dev.madlador.lexer.TokenType;
import dev.madlador.parser.ast.Assign;
import dev.madlador.parser.ast.Binary;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.Block;
import dev.madlador.parser.ast.BlockItem;
import dev.madlador.parser.ast.Constant;
import dev.madlador.parser.ast.Conditional;
import dev.madlador.parser.ast.Declaration;
import dev.madlador.parser.ast.Expression;
import dev.madlador.parser.ast.AddressOf;
import dev.madlador.parser.ast.Break;
import dev.madlador.parser.ast.Deref;
import dev.madlador.parser.ast.Index;
import dev.madlador.parser.ast.Member;
import dev.madlador.parser.ast.Cast;
import dev.madlador.parser.ast.PostfixUpdate;
import dev.madlador.parser.ast.SizeOf;
import dev.madlador.parser.ast.StringLiteral;
import dev.madlador.parser.ast.StructDeclaration;
import dev.madlador.parser.ast.Switch;
import dev.madlador.parser.ast.SwitchCase;
import dev.madlador.parser.ast.TypeRef;
import dev.madlador.parser.ast.Call;
import dev.madlador.parser.ast.GlobalDeclaration;
import dev.madlador.parser.ast.TopLevel;
import dev.madlador.parser.ast.Continue;
import dev.madlador.parser.ast.Empty;
import dev.madlador.parser.ast.ExpressionStatement;
import dev.madlador.parser.ast.For;
import dev.madlador.parser.ast.Goto;
import dev.madlador.parser.ast.If;
import dev.madlador.parser.ast.IndirectCall;
import dev.madlador.parser.ast.Initializer;
import dev.madlador.parser.ast.InitializerList;
import dev.madlador.parser.ast.While;
import dev.madlador.parser.ast.FunctionDeclaration;
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.Identifier;
import dev.madlador.parser.ast.Labeled;
import dev.madlador.parser.ast.Logical;
import dev.madlador.parser.ast.LogicalOperation;
import dev.madlador.parser.ast.Parameter;
import dev.madlador.parser.ast.Program;
import dev.madlador.parser.ast.Return;
import dev.madlador.parser.ast.Statement;
import dev.madlador.parser.ast.TypeSpecifier;
import dev.madlador.parser.ast.Unary;
import dev.madlador.parser.ast.Comma;
import dev.madlador.parser.ast.DoWhile;
import dev.madlador.parser.ast.EnumDeclaration;
import dev.madlador.parser.ast.Enumerator;
import dev.madlador.parser.ast.UnaryOperation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Recursive descent for declarations and statements, Pratt for expressions.
 *
 * <p>Expressions use precedence climbing rather than a cascade of one method per
 * level: with fourteen levels the cascade would be fourteen nearly identical
 * methods, and adding an operator would mean threading a new one through the chain.
 * Here it is a table entry in {@link Precedence}.
 *
 * <p>Errors are reported with a position and then recovered from in panic mode, so
 * one run surfaces several problems.
 */
public class Parser {

    private final List<Token> tokens;
    private final DiagnosticReporter reporter;
    private int cursor = 0;

    /** Thrown internally to unwind to the nearest recovery point. */
    private static final class ParseError extends RuntimeException {
        ParseError() {
            super(null, null, false, false);
        }
    }

    public Parser(List<Token> tokens, DiagnosticReporter reporter) {
        this.tokens = tokens;
        this.reporter = reporter;
    }

    /* ---------------- token access ---------------- */

    private Token peek() {
        return tokens.get(cursor);
    }

    private Token previous() {
        return tokens.get(Math.max(0, cursor - 1));
    }

    /**
     * The token {@code ahead} positions past the current one, clamped to the end.
     *
     * <p>Used to tell a struct declaration from a variable of struct type — what
     * follows the name is a brace in one and an identifier in the other — and to tell
     * a cast from a parenthesised expression. Two tokens is all the lookahead this
     * grammar needs anywhere.
     */
    private Token peekAhead(int ahead) {
        return tokens.get(Math.min(cursor + ahead, tokens.size() - 1));
    }

    private boolean atEnd() {
        return peek().is(TokenType.EOF);
    }

    private boolean check(TokenType type) {
        return peek().is(type);
    }

    private Token advance() {
        if (!atEnd()) cursor++;
        return previous();
    }

    private Token consume(TokenType type, String what) {
        if (check(type)) return advance();
        throw error("expected " + what + ", found " + peek().type().describe());
    }

    private ParseError error(String message) {
        reporter.error(peek().span(), message);
        return new ParseError();
    }

    private void synchronize() {
        while (!atEnd()) {
            if (previous().is(TokenType.SEMI)) return;
            switch (peek().type()) {
                case RBRACE, RETURN, IF, WHILE, FOR, BREAK, CONTINUE, SWITCH, CASE, DEFAULT, GOTO,
                     VOID, BYTE, WORD, SBYTE, SWORD, STRUCT, UNION, ENUM, CONST, STATIC -> {
                    return;
                }
                default -> advance();
            }
        }
    }

    /* ---------------- declarations ---------------- */

    public Program parseProgram() {
        Span start = peek().span();
        List<TopLevel> items = new ArrayList<>();

        while (!atEnd()) {
            try {
                items.add(topLevel());
            } catch (ParseError e) {
                // Skip to the next plausible top-level declaration so one bad
                // function does not hide every function after it.
                synchronizeTopLevel();
                if (atEnd()) break;
            }
        }
        if (items.isEmpty()) {
            if (reporter.diagnostics().isEmpty()) {
                reporter.error(peek().span(), "expected a declaration, found end of file");
            }
            throw new CompileException("nothing to compile");
        }
        return new Program(items, start.to(previous().span()));
    }

    private void synchronizeTopLevel() {
        int depth = 0;
        while (!atEnd()) {
            switch (peek().type()) {
                case LBRACE -> depth++;
                case RBRACE -> {
                    depth--;
                    if (depth <= 0) {
                        advance();
                        return;
                    }
                }
                case VOID, BYTE, WORD, SBYTE, SWORD, STRUCT, UNION, ENUM, CONST, STATIC -> {
                    if (depth == 0) return;
                }
                default -> { }
            }
            advance();
        }
    }

    /** A function definition or prototype, a global, or a struct declaration. */
    private TopLevel topLevel() {
        Span start = peek().span();
        // `struct Name {` declares a type; `struct Name x` uses one. Two tokens of
        // lookahead separate them, which is all this grammar ever needs.
        if (startsStructDeclaration()) return structDeclaration();
        if (startsEnumDeclaration()) return enumDeclaration();
        // `static` at file scope asks for internal linkage, and with one file and no
        // linker that is what everything already has. Accepted, and it changes nothing.
        Specifiers specifiers = specifiers();
        TypeRef type = typeRef(specifiers.isConst());
        if (startsFunctionPointer()) {
            Declarator declarator = functionPointer(type, Naming.REQUIRED);
            return globalDeclaration(declarator.type(), declarator.name(), start);
        }
        Token name = consume(TokenType.IDENTIFIER, "a name");

        if (check(TokenType.LPAREN)) {
            return function(type, name, start);
        }
        return globalDeclaration(withArraySuffix(type), name, start);
    }

    /** A function definition, or a prototype when a {@code ;} stands where the body would. */
    private TopLevel function(TypeRef returnType, Token name, Span start) {
        consume(TokenType.LPAREN, "'(' after the function name");
        List<Parameter> parameters = parameterList();
        consume(TokenType.RPAREN, "')' after the parameter list");

        if (check(TokenType.SEMI)) {
            Token semi = advance();
            return new FunctionDeclaration(returnType, name.lexeme(), parameters,
                    start.to(semi.span()));
        }

        List<Parameter> named = new ArrayList<>(parameters.size());
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            if (parameter.name() == null) {
                reporter.error(parameter.span(), "a parameter needs a name in a function definition",
                        "only a prototype may leave it out");
                // Carry on under a name no source can spell, so the body is still checked.
                parameter = new Parameter(parameter.type(), "#" + i, parameter.span());
            }
            named.add(parameter);
        }
        Block body = block();
        return new FunctionDefinition(returnType, name.lexeme(), named, body,
                start.to(body.span()));
    }

    private GlobalDeclaration globalDeclaration(TypeRef type, Token name, Span start) {
        if (type.isPlainVoid()) {
            reporter.error(start, "a variable may not have type 'void'");
        }
        Optional<Expression> initializer = Optional.empty();
        Optional<InitializerList> list = Optional.empty();
        if (check(TokenType.ASSIGN)) {
            advance();
            if (check(TokenType.LBRACE)) {
                list = Optional.of(initializerList());
            } else {
                initializer = Optional.of(assignmentExpression());
            }
        }
        Token semi = consume(TokenType.SEMI, "';' after the global declaration");
        return new GlobalDeclaration(type, name.lexeme(), initializer, list,
                start.to(semi.span()));
    }

    /**
     * A base type keyword with any {@code const} around it, then any number of
     * {@code *}, each of which may carry a {@code const} of its own.
     */
    private TypeRef typeRef() {
        return typeRef(false);
    }

    /** As {@link #typeRef()}, when a {@code const} was already read in front of it. */
    private TypeRef typeRef(boolean constBase) {
        while (check(TokenType.CONST)) {
            advance();
            constBase = true;
        }
        Token token = peek();
        if (!token.type().isTypeSpecifier()) {
            throw error("expected a type, found " + token.type().describe());
        }
        advance();
        TypeSpecifier base = switch (token.type()) {
            case VOID -> TypeSpecifier.VOID;
            case BYTE -> TypeSpecifier.BYTE;
            case WORD -> TypeSpecifier.WORD;
            case SBYTE -> TypeSpecifier.SBYTE;
            case SWORD -> TypeSpecifier.SWORD;
            case STRUCT -> TypeSpecifier.STRUCT;
            case UNION -> TypeSpecifier.UNION;
            case ENUM -> TypeSpecifier.ENUM;
            default -> throw new IllegalStateException("not a type: " + token.type());
        };

        Span span = token.span();
        String structName = null;
        if (base == TypeSpecifier.STRUCT || base == TypeSpecifier.UNION
                || base == TypeSpecifier.ENUM) {
            Token name = consume(TokenType.IDENTIFIER, base == TypeSpecifier.ENUM
                    ? "an enum name after 'enum'"
                    : "a " + base.spelling() + " name after '" + base.spelling() + "'");
            structName = name.lexeme();
            span = span.to(name.span());
        }
        // `byte const x` means what `const byte x` does.
        while (check(TokenType.CONST)) {
            span = span.to(advance().span());
            constBase = true;
        }

        int constLevels = constBase ? 1 : 0;
        int pointerDepth = 0;
        while (check(TokenType.STAR)) {
            span = span.to(advance().span());
            pointerDepth++;
            while (check(TokenType.CONST)) {
                span = span.to(advance().span());
                constLevels |= 1 << pointerDepth;
            }
        }
        return new TypeRef(base, pointerDepth, structName, span, constLevels);
    }

    /** {@code static} and {@code const}, in either order, ahead of a declaration's type. */
    private record Specifiers(boolean isStatic, boolean isConst) {
    }

    private Specifiers specifiers() {
        boolean isStatic = false;
        boolean isConst = false;
        while (check(TokenType.STATIC) || check(TokenType.CONST)) {
            if (advance().is(TokenType.STATIC)) {
                isStatic = true;
            } else {
                isConst = true;
            }
        }
        return new Specifiers(isStatic, isConst);
    }

    /**
     * Any number of {@code [N]} suffixes after a declared name, outermost first.
     *
     * <p>A plain number is settled here, as it always was. Anything else — an enum
     * constant, a {@code const}, a {@code sizeof}, arithmetic on them — is kept as an
     * expression for analysis, which is the only pass that knows what names mean. The
     * first dimension alone may be empty, for an initializer to count, as in C.
     */
    private TypeRef withArraySuffix(TypeRef type) {
        boolean first = true;
        while (check(TokenType.LBRACKET)) {
            Token open = advance();
            TypeRef.Dimension dimension;
            if (check(TokenType.RBRACKET)) {
                if (!first) {
                    reporter.error(open.span(), "only the first dimension of an array may be left out");
                }
                dimension = TypeRef.Dimension.unsized();
            } else if (check(TokenType.CONSTANT) && peekAhead(1).is(TokenType.RBRACKET)) {
                Token length = advance();
                if (length.value() <= 0) {
                    reporter.error(length.span(), "an array must have at least one element");
                }
                dimension = TypeRef.Dimension.of(Math.max(1, length.value()));
            } else {
                dimension = TypeRef.Dimension.computed(assignmentExpression());
            }
            Token close = consume(TokenType.RBRACKET, "']' after the array length");
            type = type.withDimension(dimension, close.span());
            first = false;
        }
        return type;
    }

    /** Whether a declared name is required, may be left out, or cannot be written. */
    private enum Naming { REQUIRED, OPTIONAL, NONE }

    /** A declared name and its type, which for a function pointer surrounds the name. */
    private record Declarator(TypeRef type, Token name) {
    }

    /** Whether a type is followed by {@code (*}, which opens a function-pointer declarator. */
    private boolean startsFunctionPointer() {
        return check(TokenType.LPAREN) && peekAhead(1).is(TokenType.STAR);
    }

    /**
     * C's function-pointer declarator after a return type: {@code (*name)(params)}, or
     * {@code (*name[4])(params)} for an array of them, or {@code (*)(params)} where no
     * name belongs, in a cast or a {@code sizeof}.
     *
     * <p>The type still begins with a keyword — the return type's — so this costs the
     * grammar nothing: whether {@code (} opens a declarator or a parameter list is
     * settled by the {@code *} after it.
     */
    private Declarator functionPointer(TypeRef returnType, Naming naming) {
        consume(TokenType.LPAREN, "'('");
        consume(TokenType.STAR, "'*'");
        Token name = null;
        if (naming != Naming.NONE && check(TokenType.IDENTIFIER)) {
            name = advance();
        } else if (naming == Naming.REQUIRED) {
            consume(TokenType.IDENTIFIER, "a name");
        }
        List<TypeRef.Dimension> arrayOf =
                withArraySuffix(TypeRef.of(TypeSpecifier.VOID, peek().span())).dimensions();
        consume(TokenType.RPAREN, "')' to close the declarator");
        consume(TokenType.LPAREN, "'(' to open the parameter types");
        List<TypeRef> parameters = new ArrayList<>();
        for (Parameter parameter : parameterList()) parameters.add(parameter.type());
        Token close = consume(TokenType.RPAREN, "')' after the parameter types");
        return new Declarator(returnType.asFunctionPointer(parameters, arrayOf, close.span()), name);
    }

    /** {@code struct Name {} } or {@code union Name {}}, as against a use of one. */
    private boolean startsStructDeclaration() {
        return (check(TokenType.STRUCT) || check(TokenType.UNION))
                && peekAhead(2).is(TokenType.LBRACE);
    }

    /**
     * {@code { a, { b, c }, "text", }} — an initializer list.
     *
     * <p>Not an expression, in C or here: it can only follow the {@code =} of a
     * declaration. C99's designators are refused by name rather than failing on the
     * {@code .} or {@code [} as a puzzling expression error.
     */
    private InitializerList initializerList() {
        Token open = consume(TokenType.LBRACE, "'{'");
        List<Initializer> items = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !atEnd()) {
            if (check(TokenType.DOT) || check(TokenType.LBRACKET)) {
                throw error("designated initializers are not supported; list the values in order");
            }
            items.add(check(TokenType.LBRACE)
                    ? initializerList()
                    : new Initializer.Value(assignmentExpression()));
            if (!check(TokenType.COMMA)) break;
            advance();
        }
        Token close = consume(TokenType.RBRACE, "'}' to close the initializer list");
        return new InitializerList(items, open.span().to(close.span()));
    }

    /** {@code enum {} } or {@code enum Name {}}, as against {@code enum Name x}, which uses one. */
    private boolean startsEnumDeclaration() {
        return check(TokenType.ENUM)
                && (peekAhead(1).is(TokenType.LBRACE) || peekAhead(2).is(TokenType.LBRACE));
    }

    /**
     * {@code enum Name { A, B = 5, C };}
     *
     * <p>A comma after the last name is allowed, as in C99, because a list that grows
     * a line at a time is exactly what an enum is.
     */
    private EnumDeclaration enumDeclaration() {
        Span start = consume(TokenType.ENUM, "'enum'").span();
        String name = check(TokenType.IDENTIFIER) ? advance().lexeme() : null;
        consume(TokenType.LBRACE, "'{' to open the enum body");

        List<Enumerator> members = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !atEnd()) {
            Token member = consume(TokenType.IDENTIFIER, "a name in the enum");
            Optional<Expression> value = Optional.empty();
            if (check(TokenType.ASSIGN)) {
                advance();
                value = Optional.of(assignmentExpression());
            }
            members.add(new Enumerator(member.lexeme(), value,
                    member.span().to(previous().span())));
            if (!check(TokenType.COMMA)) break;
            advance();
        }
        consume(TokenType.RBRACE, "'}' to close the enum body");
        Token semi = consume(TokenType.SEMI, "';' after the enum declaration");
        if (members.isEmpty()) {
            reporter.error(start.to(semi.span()), "an enum needs at least one name");
        }
        return new EnumDeclaration(name, members, start.to(semi.span()));
    }

    /**
     * {@code struct Name { type field; ... };}
     *
     * <p>One member per line, no bit-fields, no anonymous members and no
     * initializers: a struct declaration describes a layout and nothing else.
     */
    private StructDeclaration structDeclaration() {
        Token keyword = advance();
        boolean union = keyword.is(TokenType.UNION);
        Span start = keyword.span();
        Token name = consume(TokenType.IDENTIFIER, "a struct name");
        consume(TokenType.LBRACE, "'{' to open the struct body");

        List<Parameter> members = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !atEnd()) {
            Span memberStart = peek().span();
            TypeRef type = typeRef();
            Token memberName;
            if (startsFunctionPointer()) {
                Declarator declarator = functionPointer(type, Naming.REQUIRED);
                type = declarator.type();
                memberName = declarator.name();
            } else {
                if (type.isPlainVoid()) {
                    reporter.error(memberStart, "a member may not have type 'void'");
                }
                memberName = consume(TokenType.IDENTIFIER, "a member name");
                type = withArraySuffix(type);
            }
            if (type.isConst()) {
                reporter.error(memberStart, "a struct member cannot itself be const",
                        "a pointer member may point to const: 'const byte* name;'");
            }
            Token semi = consume(TokenType.SEMI, "';' after the member");
            members.add(new Parameter(type, memberName.lexeme(), memberStart.to(semi.span())));
        }
        consume(TokenType.RBRACE, "'}' to close the struct body");
        Token semi = consume(TokenType.SEMI, "';' after the struct declaration");

        if (members.isEmpty()) {
            reporter.error(start.to(semi.span()),
                    "'" + keyword.lexeme() + " " + name.lexeme() + "' has no members");
        }
        return new StructDeclaration(name.lexeme(), members, union, start.to(semi.span()));
    }

    /** Nothing, {@code void}, or a comma-separated list — the first two both mean none. */
    private List<Parameter> parameterList() {
        if (check(TokenType.RPAREN)) return List.of();
        // `f(void)` is C's spelling of an empty list; `f(void* p)` is not it.
        if (check(TokenType.VOID) && peekAhead(1).is(TokenType.RPAREN)) {
            advance();
            return List.of();
        }
        List<Parameter> parameters = new ArrayList<>();
        parameters.add(parameter());
        while (check(TokenType.COMMA)) {
            advance();
            parameters.add(parameter());
        }
        return parameters;
    }

    private Parameter parameter() {
        Span start = peek().span();
        TypeRef type = typeRef();
        if (startsFunctionPointer()) {
            Declarator declarator = functionPointer(type, Naming.OPTIONAL);
            return new Parameter(declarator.type(),
                    declarator.name() == null ? null : declarator.name().lexeme(),
                    start.to(previous().span()));
        }
        if (type.isPlainVoid()) {
            reporter.error(start, "a parameter may not have type 'void'");
        }
        // A prototype may leave the name out, as C allows. A definition may not, which
        // the caller checks once the token after ')' says which of the two this is.
        String name = null;
        if (check(TokenType.IDENTIFIER)) {
            name = advance().lexeme();
        } else if (!check(TokenType.COMMA) && !check(TokenType.RPAREN)
                && !check(TokenType.LBRACKET)) {
            consume(TokenType.IDENTIFIER, "a parameter name");
        }
        // An array parameter is a pointer to its first element, as in C: `word v[]`
        // and `word v[8]` are both a word*, and `word m[][3]` points to rows of three.
        if (check(TokenType.LBRACKET)) {
            type = withArraySuffix(type).asParameter();
        }
        return new Parameter(type, name, start.to(previous().span()));
    }

    /* ---------------- statements ---------------- */

    private Block block() {
        Token open = consume(TokenType.LBRACE, "'{'");
        List<BlockItem> items = new ArrayList<>();

        while (!check(TokenType.RBRACE) && !atEnd()) {
            try {
                items.add(blockItem());
            } catch (ParseError e) {
                synchronize();
                if (check(TokenType.RBRACE) || atEnd()) break;
            }
        }
        Token close = consume(TokenType.RBRACE, "'}' to close the block");
        return new Block(items, open.span().to(close.span()));
    }

    private BlockItem blockItem() {
        // A struct body inside a function would otherwise fail as "expected a
        // variable name", which says nothing about the actual rule. Report it and
        // then consume the whole declaration: throwing here would hand the same
        // token back to the recovery loop, which treats a type as a fresh start.
        if (startsStructDeclaration()) {
            Span where = peek().span();
            reporter.error(where, "a struct or union must be declared at file scope, not inside a function");
            structDeclaration();
            return new Empty(where);
        }
        if (startsEnumDeclaration()) return enumDeclaration();
        if (peek().type().beginsDeclaration()) return declaration();
        return statement();
    }

    private Declaration declaration() {
        Span start = peek().span();
        Specifiers specifiers = specifiers();
        TypeRef type = typeRef(specifiers.isConst());
        Token name;
        if (startsFunctionPointer()) {
            Declarator declarator = functionPointer(type, Naming.REQUIRED);
            type = declarator.type();
            name = declarator.name();
        } else {
            if (type.isPlainVoid()) {
                reporter.error(start, "a variable may not have type 'void'");
            }
            name = consume(TokenType.IDENTIFIER, "a variable name");
            type = withArraySuffix(type);
        }

        Optional<Expression> initializer = Optional.empty();
        Optional<InitializerList> list = Optional.empty();
        if (check(TokenType.ASSIGN)) {
            advance();
            if (check(TokenType.LBRACE)) {
                list = Optional.of(initializerList());
            } else {
                initializer = Optional.of(assignmentExpression());
            }
        }
        Token semi = consume(TokenType.SEMI, "';' after the declaration");
        return new Declaration(type, name.lexeme(), initializer, list, specifiers.isStatic(),
                start.to(semi.span()));
    }

    private Statement statement() {
        // `name:` can begin no expression, so one token of lookahead tells a label.
        if (check(TokenType.IDENTIFIER) && peekAhead(1).is(TokenType.COLON)) {
            return labeledStatement();
        }
        return switch (peek().type()) {
            case RETURN -> returnStatement();
            case LBRACE -> block();
            case IF -> ifStatement();
            case WHILE -> whileStatement();
            case DO -> doWhileStatement();
            case FOR -> forStatement();
            case SWITCH -> switchStatement();
            case BREAK -> breakStatement();
            case CONTINUE -> continueStatement();
            case GOTO -> gotoStatement();
            case SEMI -> new Empty(advance().span());
            default -> expressionStatement();
        };
    }

    private Statement gotoStatement() {
        Token keyword = consume(TokenType.GOTO, "'goto'");
        Token label = consume(TokenType.IDENTIFIER, "a label after 'goto'");
        Token semi = consume(TokenType.SEMI, "';' after the goto");
        return new Goto(label.lexeme(), keyword.span().to(semi.span()));
    }

    private Statement labeledStatement() {
        Token label = advance();
        consume(TokenType.COLON, "':' after the label");
        if (check(TokenType.RBRACE)) {
            throw error("a label must be followed by a statement; ';' will do");
        }
        Statement body = statement();
        return new Labeled(label.lexeme(), body, label.span().to(body.span()));
    }

    private Statement ifStatement() {
        Token keyword = consume(TokenType.IF, "'if'");
        consume(TokenType.LPAREN, "'(' after 'if'");
        Expression condition = expression();
        consume(TokenType.RPAREN, "')' after the condition");

        Statement thenBranch = statement();
        Optional<Statement> elseBranch = Optional.empty();
        if (check(TokenType.ELSE)) {
            advance();
            // `else` binds to the nearest unmatched `if`, which falls out of parsing
            // it here rather than returning to an outer level first.
            elseBranch = Optional.of(statement());
        }
        Span end = elseBranch.map(Statement::span).orElse(thenBranch.span());
        return new If(condition, thenBranch, elseBranch, keyword.span().to(end));
    }

    private Statement whileStatement() {
        Token keyword = consume(TokenType.WHILE, "'while'");
        consume(TokenType.LPAREN, "'(' after 'while'");
        Expression condition = expression();
        consume(TokenType.RPAREN, "')' after the condition");
        Statement body = statement();
        return new While(condition, body, keyword.span().to(body.span()));
    }

    private Statement doWhileStatement() {
        Token keyword = consume(TokenType.DO, "'do'");
        Statement body = statement();
        consume(TokenType.WHILE, "'while' after the body of a 'do'");
        consume(TokenType.LPAREN, "'(' after 'while'");
        Expression condition = expression();
        consume(TokenType.RPAREN, "')' after the condition");
        Token semi = consume(TokenType.SEMI, "';' after 'do ... while (...)'");
        return new DoWhile(body, condition, keyword.span().to(semi.span()));
    }

    private Statement forStatement() {
        Token keyword = consume(TokenType.FOR, "'for'");
        consume(TokenType.LPAREN, "'(' after 'for'");

        Optional<BlockItem> initializer;
        if (check(TokenType.SEMI)) {
            advance();
            initializer = Optional.empty();
        } else if (peek().type().beginsType()) {
            initializer = Optional.of(declaration());     // consumes its own ';'
        } else {
            initializer = Optional.of(expressionStatement());
        }

        Optional<Expression> condition = check(TokenType.SEMI)
                ? Optional.empty()
                : Optional.of(expression());
        consume(TokenType.SEMI, "';' after the loop condition");

        Optional<Expression> update = check(TokenType.RPAREN)
                ? Optional.empty()
                : Optional.of(expression());
        consume(TokenType.RPAREN, "')' after the for clauses");

        Statement body = statement();
        return new For(initializer, condition, update, body, keyword.span().to(body.span()));
    }

    /**
     * A switch. Arms hold a list of block items rather than a block, which is what
     * gives C's fall-through: control runs into the next arm unless it breaks out.
     */
    private Statement switchStatement() {
        Token keyword = consume(TokenType.SWITCH, "'switch'");
        consume(TokenType.LPAREN, "'(' after 'switch'");
        Expression subject = expression();
        consume(TokenType.RPAREN, "')' after the switch subject");
        consume(TokenType.LBRACE, "'{' to open the switch body");

        List<SwitchCase> cases = new ArrayList<>();
        while (!check(TokenType.RBRACE) && !atEnd()) {
            Span start = peek().span();
            Expression label = null;

            if (check(TokenType.CASE)) {
                advance();
                // The label must be a constant, which is checked in analysis; the
                // parser only needs an expression to hand it.
                label = assignmentExpression();
                consume(TokenType.COLON, "':' after the case label");
            } else if (check(TokenType.DEFAULT)) {
                advance();
                consume(TokenType.COLON, "':' after 'default'");
            } else {
                throw error("expected 'case' or 'default', found " + peek().type().describe());
            }

            List<BlockItem> body = new ArrayList<>();
            while (!check(TokenType.CASE) && !check(TokenType.DEFAULT)
                    && !check(TokenType.RBRACE) && !atEnd()) {
                try {
                    body.add(blockItem());
                } catch (ParseError e) {
                    synchronize();
                    if (check(TokenType.RBRACE) || atEnd()) break;
                }
            }
            cases.add(new SwitchCase(label, body, start.to(previous().span())));
        }

        Token close = consume(TokenType.RBRACE, "'}' to close the switch body");
        return new Switch(subject, cases, keyword.span().to(close.span()));
    }

    /** Folds a case label, reporting rather than throwing if it is not constant. */


    private Statement breakStatement() {
        Token keyword = consume(TokenType.BREAK, "'break'");
        Token semi = consume(TokenType.SEMI, "';' after 'break'");
        return new Break(keyword.span().to(semi.span()));
    }

    private Statement continueStatement() {
        Token keyword = consume(TokenType.CONTINUE, "'continue'");
        Token semi = consume(TokenType.SEMI, "';' after 'continue'");
        return new Continue(keyword.span().to(semi.span()));
    }

    private Statement returnStatement() {
        Token keyword = consume(TokenType.RETURN, "'return'");
        Optional<Expression> value = Optional.empty();
        if (!check(TokenType.SEMI)) {
            value = Optional.of(expression());
        }
        Token semi = consume(TokenType.SEMI, "';' after the return statement");
        return new Return(value, keyword.span().to(semi.span()));
    }

    private Statement expressionStatement() {
        Span start = peek().span();
        Expression expression = expression();
        Token semi = consume(TokenType.SEMI, "';' after the expression");
        return new ExpressionStatement(expression, start.to(semi.span()));
    }

    /* ---------------- expressions ---------------- */

    public Expression expression() {
        return parsePrecedence(Precedence.COMMA_EXPR);
    }

    /**
     * An expression that stops at a ',': argument lists, case labels and
     * initializers all use the comma as their own separator, so the comma
     * operator only reaches them through parentheses. This is C's
     * assignment-expression production.
     */
    private Expression assignmentExpression() {
        return parsePrecedence(Precedence.ASSIGNMENT);
    }

    /**
     * Precedence climbing: parse a prefix expression, then keep absorbing infix
     * operators while they bind at least as tightly as {@code minimum}.
     */
    private Expression parsePrecedence(int minimum) {
        Expression left = unary();

        while (true) {
            TokenType type = peek().type();
            int level = Precedence.of(type);
            if (level < minimum || level == Precedence.NONE) return left;

            Token operator = advance();

            // Right-associative levels re-enter at the same power so that
            // `a = b = c` groups as `a = (b = c)`; left-associative ones step up.
            int next = Precedence.isRightAssociative(level) ? level : level + 1;

            if (Precedence.isAssignment(type)) {
                left = assignment(left, operator, next);
                continue;
            }

            if (type == TokenType.QUESTION) {
                // The middle arm is delimited by the ':' rather than by precedence, so
                // it parses as a whole expression -- `a ? b = 1 : c` is legal C.
                Expression then = expression();
                consume(TokenType.COLON, "':' in a conditional expression");
                Expression otherwise = parsePrecedence(Precedence.CONDITIONAL);
                left = new Conditional(left, then, otherwise,
                        left.span().to(otherwise.span()));
                continue;
            }

            if (type == TokenType.COMMA) {
                Expression right = parsePrecedence(next);
                left = new Comma(left, right, left.span().to(right.span()));
                continue;
            }

            LogicalOperation logical = Precedence.logicalOperation(type);
            if (logical != null) {
                Expression right = parsePrecedence(next);
                left = new Logical(logical, left, right, left.span().to(right.span()));
                continue;
            }

            BinaryOperation binary = Precedence.binaryOperation(type);
            if (binary == null) {
                throw error("'" + operator.lexeme() + "' is not an infix operator");
            }
            Expression right = parsePrecedence(next);
            left = new Binary(binary, left, right, left.span().to(right.span()));
        }
    }

    private Expression assignment(Expression target, Token operator, int nextPrecedence) {
        Expression value = parsePrecedence(nextPrecedence);
        Span span = target.span().to(value.span());

        BinaryOperation compound = Precedence.compoundOperation(operator.type());
        if (compound == null) {
            return new Assign(target, value, span);
        }
        // `x += e` is `x = x + (e)` with the target evaluated once. A plain name gets
        // a node of its own for the read; any other place shares its node, which is
        // how lowering knows to compute the address a single time for both.
        Expression read = copyOfTarget(target);
        Expression combined = new Binary(compound, read, value, span);
        return new Assign(target, combined, span, true);
    }

    /**
     * A distinct node denoting the same place, so the read side of a compound
     * assignment does not share identity with the write side.
     */
    private Expression copyOfTarget(Expression target) {
        return switch (target) {
            case Identifier id -> new Identifier(id.name(), id.span());
            default -> target;
        };
    }

    private Expression unary() {
        Token token = peek();
        if (check(TokenType.SIZEOF)) return sizeOf();
        if (check(TokenType.PLUS_PLUS) || check(TokenType.MINUS_MINUS)) {
            // `++x` is `x += 1`: a compound assignment, which evaluates the place once
            // and yields what it now holds -- so the prefix forms need no node of their own.
            boolean increment = advance().is(TokenType.PLUS_PLUS);
            Expression target = parsePrecedence(Precedence.UNARY);
            Span span = token.span().to(target.span());
            Expression one = new Constant(1, span);
            Expression combined = new Binary(
                    increment ? BinaryOperation.ADD : BinaryOperation.SUBTRACT,
                    copyOfTarget(target), one, span);
            return new Assign(target, combined, span, true);
        }
        if (check(TokenType.AMPERSAND)) {
            advance();
            Expression operand = parsePrecedence(Precedence.UNARY);
            return new AddressOf(operand, token.span().to(operand.span()));
        }
        if (check(TokenType.STAR)) {
            advance();
            Expression operand = parsePrecedence(Precedence.UNARY);
            return new Deref(operand, token.span().to(operand.span()));
        }

        UnaryOperation operation = switch (token.type()) {
            case DASH -> UnaryOperation.NEGATE;
            case PLUS -> UnaryOperation.PLUS;
            case BANG -> UnaryOperation.NOT;
            case TILDE -> UnaryOperation.COMPLEMENT;
            default -> null;
        };
        if (operation == null) return postfix();

        advance();
        Expression operand = parsePrecedence(Precedence.UNARY);
        return new Unary(operation, operand, token.span().to(operand.span()));
    }

    /**
     * {@code sizeof(type)} or {@code sizeof(expression)}.
     *
     * <p>The parenthesis is required for both, unlike C, where {@code sizeof x} is
     * also legal. One form is easier to read and easier to explain, and nothing is
     * lost: nobody writes the other on purpose.
     *
     * <p>Which of the two it is comes from the next token. Every type in this
     * language begins with a keyword — {@code struct} included — and no expression
     * does, so there is no ambiguity to resolve and no symbol table needed.
     */
    private Expression sizeOf() {
        Span start = consume(TokenType.SIZEOF, "'sizeof'").span();
        consume(TokenType.LPAREN, "'(' after 'sizeof'");

        if (peek().type().beginsType()) {
            TypeRef type = typeRef();
            if (startsFunctionPointer()) type = functionPointer(type, Naming.NONE).type();
            Token close = consume(TokenType.RPAREN, "')' after the type");
            return SizeOf.ofType(type, start.to(close.span()));
        }
        Expression operand = expression();
        Token close = consume(TokenType.RPAREN, "')' after the operand");
        return SizeOf.ofExpression(operand, start.to(close.span()));
    }

    /** A primary expression, then any number of {@code [i]}, {@code .f} or {@code ->f}. */
    private Expression postfix() {
        Expression expression = primary();
        while (true) {
            if (check(TokenType.LBRACKET)) {
                advance();
                Expression index = expression();
                Token close = consume(TokenType.RBRACKET, "']' after the subscript");
                expression = new Index(expression, index, expression.span().to(close.span()));
            } else if (check(TokenType.DOT) || check(TokenType.ARROW)) {
                boolean throughPointer = advance().is(TokenType.ARROW);
                Token field = consume(TokenType.IDENTIFIER, "a member name");
                expression = new Member(expression, field.lexeme(), throughPointer,
                        expression.span().to(field.span()));
            } else if (check(TokenType.PLUS_PLUS) || check(TokenType.MINUS_MINUS)) {
                Token operator = advance();
                expression = new PostfixUpdate(expression,
                        operator.is(TokenType.PLUS_PLUS),
                        expression.span().to(operator.span()));
            } else if (check(TokenType.LPAREN)) {
                // A call through whatever came before: (*fp)(x), table[i](x), b->press().
                List<Expression> arguments = arguments();
                expression = new IndirectCall(expression, arguments,
                        expression.span().to(previous().span()));
            } else {
                return expression;
            }
        }
    }

    private Expression callExpression(Token callee) {
        List<Expression> arguments = arguments();
        return new Call(callee.lexeme(), arguments, callee.span().to(previous().span()));
    }

    /** {@code (a, b, c)}: a comma here separates, so each argument stops short of one. */
    private List<Expression> arguments() {
        consume(TokenType.LPAREN, "'('");
        List<Expression> arguments = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            arguments.add(assignmentExpression());
            while (check(TokenType.COMMA)) {
                advance();
                arguments.add(assignmentExpression());
            }
        }
        consume(TokenType.RPAREN, "')' to close the argument list");
        return arguments;
    }

    private Expression primary() {
        Token token = peek();
        switch (token.type()) {
            case CONSTANT, CHAR_LITERAL -> {
                advance();
                return new Constant(token.value(), token.span());
            }
            case STRING_LITERAL -> {
                advance();
                return new StringLiteral(token.lexeme(), token.span());
            }
            case IDENTIFIER -> {
                advance();
                if (check(TokenType.LPAREN)) return callExpression(token);
                return new Identifier(token.lexeme(), token.span());
            }
            case LPAREN -> {
                // A cast, if what follows the parenthesis is a type. One token decides
                // it: no expression can begin with a type keyword. See Cast's javadoc.
                if (peekAhead(1).type().beginsType()) {
                    advance();
                    TypeRef type = typeRef();
                    if (startsFunctionPointer()) type = functionPointer(type, Naming.NONE).type();
                    consume(TokenType.RPAREN, "')' after the cast type");
                    Expression operand = parsePrecedence(Precedence.UNARY);
                    return new Cast(type, operand, token.span().to(operand.span()));
                }
                advance();
                Expression inner = expression();
                consume(TokenType.RPAREN, "')' to close the expression");
                return inner;
            }
            default -> throw error("expected an expression, found " + token.type().describe());
        }
    }
}
