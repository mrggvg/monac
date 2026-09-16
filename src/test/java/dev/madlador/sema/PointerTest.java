package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pointer, array and string rules. */
class PointerTest {

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

    @Test
    @DisplayName("pointers, address-of and dereference type-check")
    void basicPointers() {
        assertClean("word main() { word x = 1; word* p = &x; return *p; }");
        assertClean("word main() { byte b = 1; byte* p = &b; return *p; }");
    }

    @Test
    @DisplayName("a non-pointer cannot be dereferenced")
    void derefNeedsPointer() {
        assertReports("word main() { word x = 1; return *x; }", "cannot dereference 'word'");
    }

    @Test
    @DisplayName("only a place has an address")
    void addressOfNeedsPlace() {
        assertReports("word main() { return &1; }", "'&' needs something with an address");
        assertReports("word main() { word x = 1; return &(x + 1); }",
                "'&' needs something with an address");
    }

    @Test
    @DisplayName("subscripting needs a pointer or array, and an integer index")
    void subscriptRules() {
        assertClean("word main() { word a[4]; a[0] = 1; return a[0]; }");
        assertReports("word main() { word x = 1; return x[0]; }", "cannot be subscripted");
    }

    @Test
    @DisplayName("assignment through a pointer or subscript is allowed")
    void assignThroughPlace() {
        assertClean("word main() { word x = 0; word* p = &x; *p = 5; return x; }");
        assertClean("word main() { word a[2]; a[1] = 5; return a[1]; }");
    }

    @Test
    @DisplayName("an array decays to a pointer to its first element")
    void arrayDecay() {
        assertClean("word main() { word a[4]; word* p = a; return *p; }");
    }

    @Test
    @DisplayName("a string literal is a byte*")
    void stringLiteralType() {
        assertClean("word main() { byte* s = \"hi\"; return s[0]; }");
        assertClean("word f(byte* s) { return s[0]; }\nword main() { return f(\"hi\"); }");
    }

    @Test
    @DisplayName("an integer converts to a pointer, which a systems toy needs")
    void integerToPointer() {
        // byte* screen = 4096; has to just work, or the display is unreachable.
        assertClean("word main() { byte* screen = 4096; screen[0] = 65; return 0; }");
    }

    @Test
    @DisplayName("pointers cannot be multiplied or divided")
    void pointerArithmeticIsRestricted() {
        assertClean("word main() { word a[4]; word* p = a; p = p + 1; return *p; }");
        assertReports("word main() { word a[4]; word* p = a; p = p * 2; return *p; }",
                "cannot be applied to");
    }

    @Test
    @DisplayName("an array needs a positive length")
    void arrayLength() {
        assertReports("word main() { word a[0]; return 0; }", "at least one element");
    }

    @Test
    @DisplayName("an array parameter is a pointer")
    void arrayParameterIsPointer() {
        assertClean("""
                word first(word a[]) { return a[0]; }
                word main() { word buf[3]; buf[0] = 9; return first(buf); }
                """);
    }
}
