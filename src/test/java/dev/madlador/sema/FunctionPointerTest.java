package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Function pointers and goto, as analysis sees them — and what they cost the stack bound. */
@DisplayName("function pointers and goto")
class FunctionPointerTest {

    private static void assertReports(String source, String expectedFragment) {
        String diagnostics = Mona.diagnose(source);
        assertTrue(diagnostics.contains(expectedFragment),
                () -> "expected a diagnostic containing '" + expectedFragment
                        + "', got:\n" + diagnostics);
    }

    private static void assertClean(String source) {
        String diagnostics = Mona.diagnose(source);
        assertFalse(diagnostics.contains("error:") || diagnostics.contains("warning:"),
                () -> "expected nothing, got:\n" + diagnostics);
    }

    @Test
    @DisplayName("a function pointer is declared the way C declares one, wherever C allows")
    void declarators() {
        assertClean("""
                word f(word x) { return x; }
                word (*g)(word) = f;
                struct S { word (*h)(word); };
                word call(word (*p)(word)) { return p(1); }
                word main() {
                    struct S s = {f};
                    word (*l)(word) = (word (*)(word))g;
                    return call(l) + s.h(2) + sizeof(word (*)(word));
                }
                """);
    }

    @Test
    @DisplayName("a global may name a function defined further down the file")
    void globalsNameLaterFunctions() {
        // Global initializers are analysed in order, so this once said the function
        // was "declared but never defined" when it was only defined later.
        assertClean("""
                word later(word n);
                word (*early)(word) = later;
                void (*table[1])(void);
                word later(word n) { return n; }
                word main() { return early(1); }
                """);
        assertReports("word never(word n); word (*p)(word) = never; word main() { return 0; }",
                "'never' is declared but never defined");
    }

    @Test
    @DisplayName("a pointer takes only a function of its own signature, or 0")
    void signatures() {
        assertReports("word f(word x) { return x; } word main() { void (*p)(void) = f; return 0; }",
                "cannot assign 'word (*)(word)' to 'void (*)(void)'");
        assertReports("word main() { word (*p)(word) = 5; return 0; }",
                "a function pointer takes a function, or 0");
        assertClean("word main() { word (*p)(word) = 0; return p == 0; }");
    }

    @Test
    @DisplayName("a call through a pointer is checked like any other")
    void indirectCalls() {
        assertReports("word f(word x) { return x; } word main() { word (*p)(word) = f; return p(1, 2); }",
                "takes 1 argument(s), but 2 were given");
        assertReports("word main() { word a[2]; return a[0](1); }",
                "not a function or a pointer to one");
        assertReports("word main() { return sizeof(*main); }", "a function has no size");
    }

    @Test
    @DisplayName("a call through a pointer reaches only functions whose address was taken")
    void stackBoundStaysFinite() {
        String deep = "word deep(word x) { word a[20]; a[0] = x; a[19] = x; return a[0] + a[19]; }\n";
        int direct = Mona.stackBound(deep + "word main() { return deep(1); }");
        int indirect = Mona.stackBound(deep
                + "word (*table[1])(word) = {deep};\n"
                + "word main() { word i = 0; return table[i](1); }");
        assertTrue(direct > 0, "a direct call has a bound: " + direct);
        assertEquals(direct, indirect, "through a table, the bound is the deepest it can reach");
    }

    @Test
    @DisplayName("a goto must name a label in its own function, and may not enter a block")
    void gotoTargets() {
        assertReports("word main() { goto nowhere; return 0; }", "there is no label 'nowhere'");
        assertReports("word main() { a: ; a: ; goto a; return 0; }", "label 'a' is already defined");
        assertReports("word main() { goto in; { in: ; } return 0; }", "jumps into a block");
        assertReports("word main() { a: return 0; }", "label 'a' is never used");
    }

    @Test
    @DisplayName("a goto may not jump forward past a declaration, but may jump back over one")
    void gotoOverDeclarations() {
        assertReports("word main() { goto l; word y = 1; l: return y; }",
                "jumps over the declaration of 'y'");
        assertClean("word main() { word n = 0; top: n++; if (n < 3) goto top; return n; }");
        assertClean("word main() { word n = 0; { goto out; } out: return n; }");
    }
}
