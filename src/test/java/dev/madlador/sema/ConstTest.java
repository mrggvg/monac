package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code const}, {@code enum} and {@code static}, as far as the analyzer is concerned.
 *
 * <p>{@code const} is mostly diagnostics, so most of what it promises is here: what may
 * not be written, through what, and where a warning replaces an error because C gives
 * one. The one change to the generated code is that a const with a constant value is
 * read as that value, which the last test pins.
 */
@DisplayName("const, enum and static")
class ConstTest {

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
    @DisplayName("a const variable cannot be assigned, stepped or compounded")
    void constVariables() {
        assertReports("word main() { const word x = 1; x = 2; return x; }", "'x' is const");
        assertReports("word main() { const word x = 1; x++; return x; }", "'x' is const");
        assertReports("word main() { const word x = 1; ++x; return x; }", "'x' is const");
        assertReports("word main() { const word x = 1; x += 1; return x; }", "'x' is const");
        assertReports("const word g = 1; word main() { g = 2; return 0; }", "'g' is const");
        assertReports("word f(const word n) { n = 1; return n; } word main() { return f(1); }",
                "'n' is const");
    }

    @Test
    @DisplayName("a pointer to const cannot be written through, but may be moved")
    void pointerToConst() {
        assertReports("word main() { word a[2]; const word* p = a; *p = 1; return 0; }",
                "is const");
        assertReports("word main() { word a[2]; const word* p = a; p[1] = 1; return 0; }",
                "is const");
        assertReports("""
                struct P { word x; };
                word main() { struct P s; const struct P* p = &s; p->x = 1; return 0; }
                """, "is const");
        assertClean("word main() { word a[2]; const word* p = a; p = &a[1]; return *p; }");
    }

    @Test
    @DisplayName("a const pointer cannot be moved, but may write what it points at")
    void constPointer() {
        assertReports("word main() { word a[2]; word* const p = a; p = a; return 0; }",
                "'p' is const");
        assertClean("word main() { word a[2]; word* const p = a; *p = 1; p[1] = 2; return a[1]; }");
    }

    @Test
    @DisplayName("a const array's elements are const, and so is anything that points at them")
    void constArraysAndAddresses() {
        assertReports("const byte t[4]; word main() { t[1] = 2; return 0; }", "is const");
        assertReports("const byte t[4]; word main() { byte* p = t; return p[0]; }",
                "drops 'const'");
        assertReports("word main() { const word x = 1; word* p = &x; return *p; }",
                "drops 'const'");
        assertClean("word main() { const word x = 1; const word* p = &x; return *p; }");
        assertClean("const byte t[4]; word main() { byte* p = (byte*)t; return p[0]; }");
    }

    @Test
    @DisplayName("const goes wherever C allows it")
    void everyPosition() {
        assertClean("""
                const byte* a;
                byte const* b;
                byte* const c = 0;
                const byte* const d = 0;
                static const word e = 5;
                word main() { return e; }
                """);
    }

    @Test
    @DisplayName("a const local needs an initializer; a static one starts at zero")
    void constLocalsNeedAValue() {
        assertReports("word main() { const word x; return 0; }", "needs an initializer");
        assertClean("word main() { static const word x; return x; }");
    }

    @Test
    @DisplayName("a const scalar with a constant initializer is itself a constant")
    void constsAreConstants() {
        assertClean("""
                const word N = 4;
                word a[N * 2];
                word main() {
                    switch (1) { case N: return 1; }
                    return sizeof(a);
                }
                """);
        assertReports("word main() { word n = 4; word a[n]; return 0; }",
                "an array length must be a constant expression");
        assertReports("word main() { const word n = __ticks(); word a[n]; return 0; }",
                "an array length must be a constant expression");
        assertReports("enum { NONE = 0 }; word a[NONE]; word main() { return 0; }",
                "at least one element");
    }

    @Test
    @DisplayName("an enum constant cannot be assigned, stepped or addressed")
    void enumConstantsAreValues() {
        assertReports("enum { A }; word main() { A = 1; return 0; }", "which is an enum constant");
        assertReports("enum { A }; word main() { A++; return 0; }", "which is an enum constant");
        assertReports("enum { A }; word main() { word p = &A; return 0; }",
                "'&' needs something with an address");
    }

    @Test
    @DisplayName("enum values must be constant, and every name new")
    void enumDeclarations() {
        assertReports("word g; enum { A = g }; word main() { return 0; }",
                "an enum value must be a constant expression");
        assertReports("enum { A, A }; word main() { return 0; }", "'A' is already declared");
        assertReports("enum { A }; word A; word main() { return 0; }", "'A' is already declared");
        assertReports("enum Color { RED }; enum Color { BLUE }; word main() { return 0; }",
                "'enum Color' is already declared");
        assertReports("word main() { enum Shade s = 0; return s; }", "unknown type 'enum Shade'");
    }

    @Test
    @DisplayName("case labels are compared by value, however they are spelled")
    void duplicateCasesThroughConstants() {
        assertReports("""
                enum { A = 2, B = 2 };
                word main() { switch (1) { case A: break; case B: break; } return 0; }
                """, "duplicate case label 2");
    }

    @Test
    @DisplayName("a static local's initializer must be a constant")
    void staticLocals() {
        assertReports("word main() { word n = 1; static word s = n; return s; }",
                "a static variable's initializer must be a constant expression");
        assertClean("word main() { static word s = 3 * 4; static byte buf[8]; return s + buf[0]; }");
    }

    @Test
    @DisplayName("a builtin that writes warns when handed a pointer to const")
    void builtinsThatWrite() {
        assertReports("const byte t[4]; byte u[4]; word main() { __memcpy(t, u, 4); return 0; }",
                "points to const");
        assertClean("const byte t[4]; byte u[4]; word main() { __memcpy(u, t, 4); return 0; }");
    }

    @Test
    @DisplayName("a const with a constant value is read as an immediate, like an enumerator")
    void constReadsFold() {
        String asm = Mona.compile("""
                const word K = 7;
                byte* const SCREEN = 4096;
                word main() { SCREEN[0] = 65; return K * 6; }
                """).assembly();
        assertFalse(asm.contains("[g_K]"), () -> "K should not be loaded:\n" + asm);
        assertFalse(asm.contains("[g_SCREEN]"), () -> "SCREEN should not be loaded:\n" + asm);
    }
}
