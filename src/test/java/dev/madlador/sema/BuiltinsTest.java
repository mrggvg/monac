package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The builtin table, checked for self-consistency.
 *
 * <p>Every builtin appears in five places — its constant, {@code isBuiltin},
 * {@code arityOf}, {@code returnTypeOf} and the lowering switch — and the four after
 * the first are easy to forget. The failure mode is quiet: an omission from
 * {@code isBuiltin} turns the name into an undeclared identifier, and an omission from
 * {@code arityOf} silently gives it whatever the fallback returns. So this walks the
 * constants by reflection rather than repeating a list that could drift.
 *
 * <p>It also pins the count, because the documentation states it: twenty-five names,
 * eight of them a single instruction.
 */
@DisplayName("the builtin table")
class BuiltinsTest {

    /** Every {@code public static final String} on the class, which is every builtin. */
    private static List<String> names() {
        List<String> names = new ArrayList<>();
        for (Field field : Builtins.class.getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            if (!Modifier.isPublic(field.getModifiers())) continue;
            if (!Modifier.isStatic(field.getModifiers())) continue;
            try {
                names.add((String) field.get(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        return names;
    }

    @Test
    @DisplayName("there are twenty-five, and every one is recognised as a builtin")
    void everyConstantIsABuiltin() {
        List<String> names = names();
        assertEquals(25, names.size(), "the docs say twenty-five: " + names);
        for (String name : names) {
            assertTrue(Builtins.isBuiltin(name),
                    () -> name + " is declared but isBuiltin does not know it");
            assertTrue(name.startsWith("__"), () -> name + " should be underscore-prefixed");
        }
    }

    @Test
    @DisplayName("eight are a single instruction and seventeen are runtime calls")
    void theSplitIsWhatTheDocsClaim() {
        int calls = 0;
        for (String name : names()) {
            if (Builtins.isHeapBuiltin(name) || Builtins.isRuntimeCall(name)) calls++;
            // Nothing can be both: the heap pair also decide the program has a heap,
            // which the other helpers have no bearing on.
            assertFalse(Builtins.isHeapBuiltin(name) && Builtins.isRuntimeCall(name),
                    () -> name + " cannot be classified as both");
        }
        assertEquals(17, calls, "the heap pair plus fifteen helpers");
        assertEquals(8, names().size() - calls, "and the rest are one instruction each");
    }

    @Test
    @DisplayName("each one's arity is what its signature says")
    void aritiesAreDeclared() {
        assertEquals(1, Builtins.arityOf(Builtins.IN));
        assertEquals(2, Builtins.arityOf(Builtins.OUT));
        assertEquals(0, Builtins.arityOf(Builtins.STI));
        assertEquals(0, Builtins.arityOf(Builtins.CLI));
        assertEquals(1, Builtins.arityOf(Builtins.SETISR));
        assertEquals(1, Builtins.arityOf(Builtins.ALLOC));
        assertEquals(1, Builtins.arityOf(Builtins.FREE));
        assertEquals(3, Builtins.arityOf(Builtins.VWRITE));
        assertEquals(3, Builtins.arityOf(Builtins.VREAD));
        assertEquals(3, Builtins.arityOf(Builtins.VFILL));
        assertEquals(0, Builtins.arityOf(Builtins.WAITFRAME));
        assertEquals(2, Builtins.arityOf(Builtins.MULHI));
        assertEquals(0, Builtins.arityOf(Builtins.TICKS));
        assertEquals(0, Builtins.arityOf(Builtins.GETKEY));
        assertEquals(0, Builtins.arityOf(Builtins.HALT));
        assertEquals(3, Builtins.arityOf(Builtins.MEMCPY));
        assertEquals(3, Builtins.arityOf(Builtins.MEMSET));
        assertEquals(1, Builtins.arityOf(Builtins.STRLEN));
        assertEquals(2, Builtins.arityOf(Builtins.STRCPY));
        assertEquals(2, Builtins.arityOf(Builtins.STRCMP));
        assertEquals(1, Builtins.arityOf(Builtins.SQRT));
        assertEquals(1, Builtins.arityOf(Builtins.SIN));
        assertEquals(1, Builtins.arityOf(Builtins.COS));
        assertEquals(2, Builtins.arityOf(Builtins.FIXMUL));
        assertEquals(0, Builtins.arityOf(Builtins.RANDOM));
    }

    @Test
    @DisplayName("eight produce a word, four a signed word, and the rest nothing")
    void returnTypes() {
        Set<String> words = Set.of(Builtins.IN, Builtins.ALLOC, Builtins.MULHI, Builtins.TICKS,
                Builtins.GETKEY, Builtins.STRLEN, Builtins.SQRT, Builtins.RANDOM);
        Set<String> signed = Set.of(Builtins.STRCMP, Builtins.SIN, Builtins.COS, Builtins.FIXMUL);
        for (String name : names()) {
            Type expected = words.contains(name) ? Type.WORD
                    : signed.contains(name) ? Type.SWORD : Type.VOID;
            assertEquals(expected, Builtins.returnTypeOf(name), name);
        }
    }

    @Test
    @DisplayName("a wrong argument count is a diagnostic, not a miscompile")
    void arityIsEnforced() {
        String diagnostics = Mona.diagnose("word main() { __vwrite(1, 2); return 0; }");
        assertTrue(diagnostics.contains("takes 3 argument(s)"),
                () -> "expected an arity error, got:\n" + diagnostics);

        assertTrue(Mona.diagnose("word main() { __waitframe(1); return 0; }")
                .contains("takes 0 argument(s)"));
        assertTrue(Mona.diagnose("word main() { return __mulhi(1); }")
                .contains("takes 2 argument(s)"));
        assertTrue(Mona.diagnose("word main() { return __ticks(1); }")
                .contains("takes 0 argument(s)"));
        assertTrue(Mona.diagnose("word main() { return __strlen(); }")
                .contains("takes 1 argument(s)"));
    }

    @Test
    @DisplayName("a void builtin cannot be used for its value")
    void voidBuiltinsAreNotValues() {
        String diagnostics = Mona.diagnose("word main() { return __waitframe(); }");
        assertFalse(diagnostics.isEmpty(),
                "returning the result of a void builtin should be reported");
    }

    @Test
    @DisplayName("an array may be passed where a builtin wants an address")
    void arraysDecay() {
        String diagnostics = Mona.diagnose("""
                byte a[4];
                byte b[4];
                word main() { __memcpy(a, b, 4); return __strlen(a); }
                """);
        assertFalse(diagnostics.contains("error:"), diagnostics);
    }
}
