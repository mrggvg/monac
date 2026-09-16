package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The C-library builtins: memory, strings, and the arithmetic this machine makes hard.
 *
 * <p>Each runs on the real simulator. The memory and string ones are checked against
 * what C's would do, including the edges — overlap, an empty count, bytes above 127 —
 * where a hand-written loop tends to be wrong. The arithmetic ones are checked against
 * Java computing the same thing, over every boundary plus a few hundred random inputs
 * from a fixed seed.
 */
@DisplayName("library builtins: memory, strings, square root, sine, fixed point")
class LibraryBuiltinsIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** Runs a program and insists it returned 0; any other value is the failing check. */
    private static void ok(String source) {
        Oracle.Result r = Oracle.get().run(Mona.compile(source).assembly(), 2_000_000);
        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), () -> "check " + r.a() + " failed\n" + r.describe());
    }

    /**
     * Checks that each expression evaluates to its expected value, as a 16-bit pattern,
     * in batches small enough to fit the machine, and names the first that does not.
     */
    private static void agree(List<String> expressions, List<Integer> expected) {
        final int perBatch = 80;
        for (int start = 0; start < expressions.size(); start += perBatch) {
            int end = Math.min(start + perBatch, expressions.size());
            StringBuilder source = new StringBuilder("word main() {\n");
            for (int i = start; i < end; i++) {
                source.append("    if ((word)(").append(expressions.get(i)).append(") != ")
                        .append(expected.get(i) & 0xFFFF).append(") return ")
                        .append(i - start + 1).append(";\n");
            }
            source.append("    return 0;\n}\n");
            Oracle.Result r = Oracle.get().run(Mona.compile(source.toString()).assembly(), 9_000_000);
            assertTrue(r.ok(), r::describe);
            if (r.a() != 0) {
                int i = start + r.a() - 1;
                fail(expressions.get(i) + " should be " + expected.get(i)
                        + " (" + (expected.get(i) & 0xFFFF) + ")");
            }
        }
    }

    @Test
    @DisplayName("__memcpy copies, and survives overlap in both directions")
    void memcpy() {
        ok("""
                byte a[12];
                byte b[4];
                word main() {
                    for (word i = 0; i < 12; i++) a[i] = i + 1;
                    // Up by two, overlapping: a forward copy would smear 1 2 across.
                    __memcpy(&a[2], &a[0], 8);
                    if (a[0] != 1 || a[1] != 2) return 1;
                    for (word i = 2; i < 10; i++) if (a[i] != i - 1) return 2;
                    if (a[10] != 11 || a[11] != 12) return 3;
                    // Down by one, overlapping the other way.
                    __memcpy(&a[0], &a[1], 11);
                    if (a[0] != 2 || a[1] != 1 || a[9] != 11 || a[10] != 12) return 4;
                    if (a[11] != 12) return 5;
                    // Nothing, in either direction.
                    __memcpy(&a[0], &a[5], 0);
                    __memcpy(&a[5], &a[0], 0);
                    if (a[0] != 2 || a[5] != 5) return 6;
                    // Between separate buffers, passing the arrays themselves.
                    __memcpy(b, a, 4);
                    if (b[0] != 2 || b[1] != 1 || b[2] != 2 || b[3] != 3) return 7;
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("__memset stores the low byte, exactly count times")
    void memset() {
        ok("""
                byte a[8];
                word main() {
                    for (word i = 0; i < 8; i++) a[i] = 9;
                    __memset(&a[1], 0x1234, 6);
                    if (a[0] != 9 || a[7] != 9) return 1;
                    for (word i = 1; i < 7; i++) if (a[i] != 0x34) return 2;
                    __memset(a, 0, 0);
                    if (a[0] != 9) return 3;
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("__strlen, __strcpy and __strcmp behave as C's")
    void strings() {
        ok("""
                byte buf[16];
                word main() {
                    if (__strlen("") != 0) return 1;
                    if (__strlen("hello") != 5) return 2;
                    for (word i = 0; i < 16; i++) buf[i] = 'x';
                    __strcpy(buf, "abc");
                    if (buf[0] != 'a' || buf[2] != 'c' || buf[3] != 0) return 3;
                    if (buf[4] != 'x') return 4;
                    if (__strlen(buf) != 3) return 5;
                    if (__strcmp(buf, "abc") != 0) return 6;
                    if (__strcmp("abc", "abd") >= 0) return 7;
                    if (__strcmp("abd", "abc") <= 0) return 8;
                    if (__strcmp("ab", "abc") >= 0) return 9;
                    if (__strcmp("abc", "ab") != 'c') return 10;
                    if (__strcmp("\\xC8", "a") <= 0) return 11;
                    if (__strcmp("", "") != 0) return 12;
                    __strcpy(buf, "");
                    if (buf[0] != 0 || __strlen(buf) != 0) return 13;
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("__sqrt is the floor of the square root, at every square and either side")
    void sqrt() {
        TreeSet<Integer> inputs = new TreeSet<>();
        for (int k = 0; k <= 256; k++) {
            for (int d = -1; d <= 1; d++) {
                int n = k * k + d;
                if (n >= 0 && n <= 65535) inputs.add(n);
            }
        }
        Random random = new Random(20260911L);
        for (int i = 0; i < 300; i++) inputs.add(random.nextInt(65536));

        List<String> expressions = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int n : inputs) {
            expressions.add("__sqrt(" + n + ")");
            expected.add((int) Math.floor(Math.sqrt(n)));
        }
        agree(expressions, expected);
    }

    /** What the table promises: the rounded sine, 256 steps a turn, 1.0 as 256. */
    private static int sine(int angle) {
        return (int) Math.round(StrictMath.sin((angle & 255) * Math.PI / 128) * 256);
    }

    @Test
    @DisplayName("__sin and __cos match the rounded sine at every one of the 256 angles")
    void sinAndCos() {
        List<String> expressions = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        List<Integer> angles = new ArrayList<>();
        for (int a = 0; a < 256; a++) angles.add(a);
        // Past one turn it wraps, whether the angle is merely large or wraps the word.
        angles.addAll(List.of(256, 300, 1000, 65472, 65535));
        for (int a : angles) {
            expressions.add("__sin(" + a + ")");
            expected.add(sine(a));
            expressions.add("__cos(" + a + ")");
            expected.add(sine(a + 64));
        }
        agree(expressions, expected);
    }

    /** Bits 8 to 23 of the signed 32-bit product, as a 16-bit pattern. */
    private static int fixmul(int a, int b) {
        return (int) (((long) (short) a * (short) b) >> 8) & 0xFFFF;
    }

    @Test
    @DisplayName("__fixmul is (a * b) >> 8 on the full signed product")
    void fixmul() {
        int[] edges = {0, 1, -1, 2, -2, 127, 128, -128, 255, 256, -256, 257,
                       12345, -12345, 32767, -32768};
        List<int[]> pairs = new ArrayList<>();
        for (int a : edges) {
            for (int b : edges) pairs.add(new int[]{a, b});
        }
        Random random = new Random(1911L);
        for (int i = 0; i < 400; i++) {
            pairs.add(new int[]{random.nextInt(65536) - 32768, random.nextInt(65536) - 32768});
        }
        List<String> expressions = new ArrayList<>();
        List<Integer> expected = new ArrayList<>();
        for (int[] pair : pairs) {
            expressions.add("__fixmul(" + (pair[0] & 0xFFFF) + ", " + (pair[1] & 0xFFFF) + ")");
            expected.add(fixmul(pair[0], pair[1]));
        }
        agree(expressions, expected);
    }

    @Test
    @DisplayName("__random reads a fresh word each time")
    void random() {
        // RNDGEN is registered only with the peripherals attached; without them port
        // 10 does not exist and reading it faults, which is not what is under test.
        String source = """
                word main() {
                    word first = __random();
                    for (word i = 0; i < 8; i++) if (__random() != first) return 0;
                    return 1;
                }
                """;
        Oracle.Result r = Oracle.get().run(
                Mona.compile(source).assembly(), 100_000, null, null, null, 0, true);
        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), "nine reads of RNDGEN all came back the same");
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
        return n;
    }

    @Test
    @DisplayName("each costs nothing until used, and sine and cosine share one table")
    void emittedOnlyWhenUsed() {
        String none = Mona.compile("word main() { return 0; }").assembly();
        for (String label : List.of(".rt_memcpy:", ".rt_memset:", ".rt_strlen:", ".rt_strcpy:",
                ".rt_strcmp:", ".rt_sqrt:", ".rt_sin:", ".rt_fixmul:", "rt_sin_table:")) {
            assertFalse(none.contains(label), () -> label + " emitted by a program that never uses it");
        }
        String both = Mona.compile("word main() { return (word)(__sin(3) + __cos(3)); }").assembly();
        assertEquals(1, count(both, "rt_sin_table:"), "one table for both");
        String cosOnly = Mona.compile("word main() { return (word)__cos(3); }").assembly();
        assertEquals(1, count(cosOnly, "rt_sin_table:"), "cosine alone still brings the table");
        String fix = Mona.compile("word main() { return (word)__fixmul(3, 4); }").assembly();
        assertEquals(1, count(fix, ".rt_mulhi:"), "__fixmul brings __mulhi, once");
    }
}
