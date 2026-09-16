package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What happens when the stack runs out.
 *
 * <p>Without the guard this machine gives no useful answer. The stack grows down into
 * the code, overwrites it, and the program dies of {@code Invalid opcode: 255} at some
 * unrelated address — or, if it gets that far, of a memory access violation once
 * {@code SP} has wrapped past zero. Both happen well after the evidence has been
 * destroyed.
 *
 * <p>These run on the real simulator, because the whole point is what the machine does
 * rather than what the compiler emitted.
 */
@DisplayName("stack overflow")
class StackOverflowIT {

    /** The memory-mapped text display: thirty-two characters at 0x1000. */
    private static final int[] DISPLAY = {0x1000, 0x1020};

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    private static Oracle.Result run(String source) {
        return Oracle.get().run(Mona.compile(source).assembly(), 2_000_000, DISPLAY);
    }

    private static String screen(Oracle.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int b : result.memory()) sb.append(b == 0 ? ' ' : (char) b);
        return sb.toString().stripTrailing();
    }

    @Test
    @DisplayName("a runaway recursion says so, on the display, and stops")
    void runawayRecursionReports() {
        Oracle.Result r = run("""
                word deep(word n) {
                    return deep(n + 1) + 1;
                }
                word main() { return deep(1); }
                """);

        assertTrue(r.ok(), r::describe);
        assertEquals("STACK OVERFLOW", screen(r),
                () -> "the display should say what went wrong\n" + r.describe());
        assertTrue(r.halted(), () -> "it should stop, not run on\n" + r.describe());
        assertFalse(r.fault(),
                () -> "and stop cleanly, rather than by crashing into its own code\n"
                        + r.describe());
    }

    @Test
    @DisplayName("it stops before overwriting the program")
    void stopsWhileTheCodeIsStillIntact() {
        // The reporter runs off the same image the recursion was eating into, so the
        // fact that a readable message appears at all is the evidence: had the stack
        // reached the code, this would have been an invalid opcode instead.
        Oracle.Result r = run("""
                word deep(word n) {
                    word a = n + 1;
                    word b = a + 1;
                    return deep(a) + b;
                }
                word main() { return deep(1); }
                """);

        assertTrue(r.ok(), r::describe);
        assertEquals("STACK OVERFLOW", screen(r), r::describe);
        assertTrue(r.sp() > 0,
                () -> "SP should be stopped above zero, not wrapped past it\n" + r.describe());
    }

    @Test
    @DisplayName("mutual recursion is caught too")
    void mutualRecursionReports() {
        Oracle.Result r = run("""
                word ping(word n) { return pong(n + 1) + 1; }
                word pong(word n) { return ping(n + 1) + 1; }
                word main() { return ping(1); }
                """);

        assertTrue(r.ok(), r::describe);
        assertEquals("STACK OVERFLOW", screen(r), r::describe);
        assertFalse(r.fault(), r::describe);
    }

    @Test
    @DisplayName("a recursion that terminates is not disturbed by the check")
    void boundedRecursionStillWorks() {
        Oracle.Result r = run("""
                word fact(word n) {
                    if (n <= 1) return 1;
                    return n * fact(n - 1);
                }
                word main() { return fact(8); }
                """);

        assertTrue(r.ok(), r::describe);
        assertEquals(40320, r.a(), () -> "8! should still come out\n" + r.describe());
        assertEquals(0x0FFF, r.sp(), () -> "and the stack should balance\n" + r.describe());
        assertEquals("", screen(r), "nothing should have been written to the display");
    }

    @Test
    @DisplayName("a program that cannot recurse carries no guard at all")
    void noGuardWithoutRecursion() {
        // The cost of the check is two instructions per call, and the reason it is
        // affordable is that it appears only where it can fire.
        String assembly = Mona.compile("""
                word add(word a, word b) { return a + b; }
                word main() { return add(1, 2); }
                """).assembly();

        assertFalse(assembly.contains("rt_stack_limit"),
                () -> "no check, no limit, no reporter:\n" + assembly);
        assertFalse(assembly.contains("STACK OVERFLOW"),
                () -> "and none of the message either:\n" + assembly);
    }

    @Test
    @DisplayName("recursion with a heap is guarded above the heap, not above the code")
    void guardSitsAboveTheHeap() {
        // The floor is wherever the stack must not go, and with a heap that is the top
        // of the heap rather than the end of the image. Getting this wrong would let
        // the stack eat the heap in silence.
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; };
                word deep(word n) {
                    struct Node* node = __alloc(sizeof(struct Node));
                    if (node != 0) node->value = n;
                    return deep(n + 1) + 1;
                }
                word main() { return deep(1); }
                """);

        assertTrue(r.ok(), r::describe);
        assertEquals("STACK OVERFLOW", screen(r), r::describe);
        assertFalse(r.fault(), r::describe);
    }
}
