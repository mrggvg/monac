package dev.madlador.parser;

import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.lexer.Lexer;
import dev.madlador.parser.ast.AstPrinter;
import dev.madlador.parser.ast.FunctionDeclaration;
import dev.madlador.parser.ast.GlobalDeclaration;
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.TypeRef;
import dev.madlador.parser.ast.Program;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    private DiagnosticReporter reporter;
    private SourceFile file;

    private Program parse(String source) {
        file = new SourceFile("test.mona", source);
        reporter = new DiagnosticReporter(file);
        return new Parser(new Lexer(file, reporter).tokenize(), reporter).parseProgram();
    }

    private String parseErrors(String source) {
        try {
            parse(source);
        } catch (CompileException ignored) {
            // errors are in the reporter either way
        }
        return reporter.render();
    }

    @Test
    @DisplayName("a simple function parses to the expected tree")
    void parsesSimpleFunction() {
        Program program = parse("word add(word a, word b) { return a + b; }");
        assertFalse(reporter.hasErrors(), reporter.render());
        assertEquals("""
                Program
                  Function add -> word
                    Param a: word
                    Param b: word
                    Block
                      Return
                        Binary ADD
                          Identifier a
                          Identifier b
                """, AstPrinter.print(program));
    }

    @Test
    @DisplayName("multiplication binds tighter than addition")
    void precedence() {
        Program program = parse("word f() { return 1 + 2 * 3; }");
        assertFalse(reporter.hasErrors(), reporter.render());
        String tree = AstPrinter.print(program);
        assertTrue(tree.contains(
                "        Binary ADD\n"
                        + "          Constant 1\n"
                        + "          Binary MULTIPLY\n"), tree);
    }

    @Test
    @DisplayName("parentheses override precedence")
    void parentheses() {
        String tree = AstPrinter.print(parse("word f() { return (1 + 2) * 3; }"));
        assertTrue(tree.contains(
                "        Binary MULTIPLY\n"
                        + "          Binary ADD\n"), tree);
    }

    @Test
    @DisplayName("subtraction is left associative")
    void leftAssociative() {
        // 10 - 3 - 2 must be (10 - 3) - 2, not 10 - (3 - 2)
        String tree = AstPrinter.print(parse("word f() { return 10 - 3 - 2; }"));
        assertTrue(tree.contains(
                "        Binary SUBTRACT\n"
                        + "          Binary SUBTRACT\n"
                        + "            Constant 10\n"
                        + "            Constant 3\n"
                        + "          Constant 2\n"), tree);
    }

    @Test
    @DisplayName("declarations with and without initializers parse")
    void declarations() {
        Program program = parse("word f() { word x; word y = 4; y = x + 1; }");
        assertFalse(reporter.hasErrors(), reporter.render());
        String tree = AstPrinter.print(program);
        assertTrue(tree.contains("Declare x: word"), tree);
        assertTrue(tree.contains("Declare y: word"), tree);
        assertTrue(tree.contains("Assign\n") && tree.contains("Identifier y"), tree);
    }

    @Test
    @DisplayName("the signed type keywords parse")
    void signedTypes() {
        String tree = AstPrinter.print(parse("sword f(sbyte a) { return a; }"));
        assertTrue(tree.contains("Function f -> sword"), tree);
        assertTrue(tree.contains("Param a: sbyte"), tree);
    }

    @Test
    @DisplayName("errors carry a line and column")
    void errorsArePositioned() {
        String errors = parseErrors("word f() {\n    return 1 +;\n}");
        assertTrue(errors.contains("test.mona:2:15"), errors);
        assertTrue(errors.contains("expected an expression"), errors);
    }

    @Test
    @DisplayName("a missing semicolon names the token that was expected")
    void missingSemicolon() {
        String errors = parseErrors("word f() { return 1 }");
        assertTrue(errors.contains("expected ';'"), errors);
    }

    @Test
    @DisplayName("recovery reports several errors in one run")
    void reportsMultipleErrors() {
        // The old parser threw on the first problem, so you fixed errors one at a time.
        String errors = parseErrors("""
                word f() {
                    word x = ;
                    word y = ;
                    word z = ;
                }
                """);
        long count = errors.lines().filter(l -> l.contains("error:")).count();
        assertTrue(count >= 3, "expected at least 3 errors, got " + count + ":\n" + errors);
    }

    @Test
    @DisplayName("a void variable is rejected")
    void voidVariable() {
        String errors = parseErrors("word f() { void x; }");
        assertTrue(errors.contains("may not have type 'void'"), errors);
    }

    @Test
    @DisplayName("several functions and globals parse in one file")
    void multipleTopLevelItems() {
        Program program = parse("word g = 1; word f() { return g; } word main() { return f(); }");
        assertFalse(reporter.hasErrors(), reporter.render());
        assertEquals(3, program.items().size());
    }

    @Test
    @DisplayName("a call parses with its arguments")
    void callExpression() {
        String tree = AstPrinter.print(parse("word main() { return f(1, 2); }"));
        assertTrue(tree.contains("Call f"), tree);
        assertTrue(tree.contains("Constant 1"), tree);
        assertTrue(tree.contains("Constant 2"), tree);
    }

    @Test
    @DisplayName("an empty file fails with a diagnostic rather than a crash")
    void emptyFile() {
        assertThrows(CompileException.class, () -> parse(""));
        assertTrue(reporter.hasErrors());
        assertTrue(reporter.render().contains("expected a declaration"), reporter.render());
    }

    @Test
    @DisplayName("node spans point back at the source text")
    void spansAreUsable() {
        Program program = parse("word f() { return 42; }");
        assertEquals("word f() { return 42; }", file.textOf(program.span()));
        FunctionDefinition f = (FunctionDefinition) program.items().get(0);
        assertEquals("{ return 42; }", file.textOf(f.body().span()));
    }

    @Test
    @DisplayName("a ';' in place of the body makes a prototype, whose names are optional")
    void prototype() {
        Program program = parse("word f(word, byte* p); void g(void); word h(word[]);");
        FunctionDeclaration f = (FunctionDeclaration) program.items().get(0);
        assertEquals(2, f.parameters().size());
        assertNull(f.parameters().get(0).name());
        assertEquals("p", f.parameters().get(1).name());
        FunctionDeclaration g = (FunctionDeclaration) program.items().get(1);
        assertTrue(g.parameters().isEmpty());
        FunctionDeclaration h = (FunctionDeclaration) program.items().get(2);
        assertTrue(h.parameters().get(0).type().isPointer());   // an array parameter decays
        assertFalse(reporter.hasErrors(), reporter.render());
    }

    @Test
    @DisplayName("const may qualify the base type and each pointer level separately")
    void constLevels() {
        Program program = parse(
                "const byte* const p; byte const q; word* const* r; static word s;");
        TypeRef p = ((GlobalDeclaration) program.items().get(0)).type();
        assertTrue(p.isConstAt(0) && p.isConstAt(1) && p.isConst());
        TypeRef q = ((GlobalDeclaration) program.items().get(1)).type();
        assertTrue(q.isConst());
        TypeRef r = ((GlobalDeclaration) program.items().get(2)).type();
        assertEquals(2, r.pointerDepth());
        assertFalse(r.isConstAt(0));
        assertTrue(r.isConstAt(1));
        assertFalse(r.isConst());
        assertEquals("word* const*", r.display());
        assertFalse(reporter.hasErrors(), reporter.render());
    }
}
