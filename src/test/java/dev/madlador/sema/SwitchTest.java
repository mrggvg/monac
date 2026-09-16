package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Switch rules, and the choice between a jump table and a chain of comparisons.
 *
 * <p>The strategy is not observable from the program's result, only from the code,
 * so it is checked by reading the assembly. A table costs two bytes per entry across
 * the whole span of labels, so it only pays when the labels actually fill it.
 */
class SwitchTest {

    private static void assertReports(String source, String expectedFragment) {
        String diagnostics = Mona.diagnose(source);
        assertTrue(diagnostics.contains(expectedFragment),
                () -> "expected a diagnostic containing '" + expectedFragment
                        + "', got:\n" + diagnostics);
    }

    private static void assertClean(String source) {
        String diagnostics = Mona.diagnose(source);
        assertFalse(diagnostics.contains("error:"),
                () -> "expected no errors, got:\n" + diagnostics);
    }

    /** A jump table is the only thing that emits an indirect jump. */
    private static boolean usesJumpTable(String source) {
        return Mona.compile(source).assembly().contains("JMP   [A]");
    }

    @Test
    @DisplayName("dense labels compile to a jump table")
    void denseUsesTable() {
        assertTrue(usesJumpTable("""
                word f(word n) {
                    switch (n) {
                        case 0: return 1;
                        case 1: return 2;
                        case 2: return 3;
                        case 3: return 4;
                        default: return 0;
                    }
                }
                word main() { return f(2); }
                """));
    }

    @Test
    @DisplayName("sparse labels compile to a chain of comparisons")
    void sparseUsesChain() {
        // A table spanning 1..500 would be a thousand bytes to hold two entries.
        assertFalse(usesJumpTable("""
                word f(word n) {
                    switch (n) {
                        case 1: return 10;
                        case 500: return 20;
                        default: return 30;
                    }
                }
                word main() { return f(1); }
                """));
    }

    @Test
    @DisplayName("too few labels is not worth a table")
    void fewCasesUseChain() {
        assertFalse(usesJumpTable("""
                word f(word n) {
                    switch (n) {
                        case 0: return 1;
                        case 1: return 2;
                        default: return 0;
                    }
                }
                word main() { return f(1); }
                """));
    }

    @Test
    @DisplayName("a duplicate case label is an error")
    void duplicateCase() {
        assertReports("""
                word main() {
                    switch (1) {
                        case 2: return 1;
                        case 2: return 2;
                    }
                    return 0;
                }
                """, "duplicate case label 2");
    }

    @Test
    @DisplayName("only one default is allowed")
    void repeatedDefault() {
        assertReports("""
                word main() {
                    switch (1) {
                        default: return 1;
                        default: return 2;
                    }
                }
                """, "only one 'default'");
    }

    @Test
    @DisplayName("a case label must be a constant")
    void nonConstantCase() {
        assertReports("""
                word main() {
                    word x = 1;
                    switch (x) {
                        case x: return 1;
                    }
                    return 0;
                }
                """, "must be a constant expression");
    }

    @Test
    @DisplayName("a constant expression is a fine case label")
    void foldedCaseLabel() {
        assertClean("""
                word main() {
                    switch (6) {
                        case 2 * 3: return 1;
                        default: return 0;
                    }
                }
                """);
    }

    @Test
    @DisplayName("break is allowed in a switch outside any loop")
    void breakInSwitch() {
        assertClean("""
                word main() {
                    switch (1) {
                        case 1: break;
                        default: break;
                    }
                    return 0;
                }
                """);
        assertReports("word main() { switch (1) { case 1: continue; } return 0; }",
                "'continue' outside of a loop");
    }
}
