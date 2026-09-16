package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Initializer lists, unions and arrays of arrays, as the analyzer sees them. */
@DisplayName("initializers, unions and dimensions")
class InitializerTest {

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
    @DisplayName("a list may not say more than the object holds")
    void tooMany() {
        assertReports("word a[2] = {1, 2, 3}; word main() { return 0; }", "too many values for 'word[2]'");
        assertReports("word main() { word x = {5, 6}; return x; }", "takes one value, not a list");
        assertReports("byte s[2] = \"abc\"; word main() { return 0; }", "holds 2");
    }

    @Test
    @DisplayName("a global's values must be known before the program runs")
    void globalsAreConstant() {
        assertReports("word g; word a[2] = {g, 1}; word main() { return 0; }",
                "a global initializer must be a constant expression");
        assertReports("word t[4]; word* p = &t[1]; word main() { return 0; }",
                "a global initializer must be a constant expression");
        assertReports("byte b = \"x\"; word main() { return b; }", "an address takes two bytes");
        assertClean("""
                const byte* names[2] = {"a", "b"};
                word g;
                word* pg = &g;
                word table[3];
                word* pt = table;
                void f() { }
                word h = (word)&f;
                word main() { return 0; }
                """);
    }

    @Test
    @DisplayName("an array is initialized with a list, and never assigned whole")
    void arrays() {
        assertReports("word a[2] = 5; word main() { return 0; }", "an array is initialized with a list");
        assertReports("word main() { word a[2]; word b[2]; a = b; return 0; }",
                "cannot assign to an array");
        assertReports("word main() { word a[]; return 0; }", "needs an initializer to count");
        assertReports("word a[2][]; word main() { return 0; }", "only the first dimension");
        assertReports("word a[2] = {[1] = 5}; word main() { return 0; }",
                "designated initializers are not supported");
    }

    @Test
    @DisplayName("struct and union share one namespace, as in C")
    void unionsAndStructs() {
        assertReports("union U { word x; }; struct U s; word main() { return 0; }",
                "'U' is a union, not a struct");
        assertReports("struct S { word x; }; union S { word y; }; word main() { return 0; }",
                "'union S' is already declared");
        assertClean("union U { word x; byte y[5]; }; word main() { union U u; return sizeof(u); }");
    }

    @Test
    @DisplayName("an array parameter may be written with its lengths")
    void arrayParameters() {
        assertClean("""
                word first(word v[8]) { return v[0]; }
                word corner(word m[][3]) { return m[1][2]; }
                word main() { word a[8]; word b[2][3]; return first(a) + corner(b); }
                """);
    }
}
