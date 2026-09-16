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
 * Allocating from an interrupt handler and from the program at once.
 *
 * <p>No allocator here is re-entrant, and none can be made so for a price worth
 * paying: each reads a word, decides from it, and writes it back, so an interrupt
 * landing in the middle leaves the handler working from a value the interrupted code
 * was about to change. Both are then handed the same memory.
 *
 * <p>The first test is the demonstration rather than the check — it runs the race and
 * shows the collision, which is why the warning exists. The rest are the warning
 * itself, and they matter in both directions: the machine masks interrupts for the
 * duration of a handler, so a handler cannot interrupt itself, and warning about a
 * program where only one side allocates would be noise.
 */
@DisplayName("interrupt safety")
class InterruptSafetyIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** Both sides allocate, and the pointers are compared for overlap. */
    private static final String RACING = """
            word COUNT = 30;
            struct Cell { word stamp; word spare; };
            struct Cell* mine[30];
            struct Cell* theirs[30];
            word theirCount = 0;

            void onIrq() {
                word pending = __in(1);
                if (theirCount < 30) {
                    struct Cell* got = __alloc(sizeof(struct Cell));
                    if (got != 0) {
                        theirs[theirCount] = got;
                        theirCount = theirCount + 1;
                    }
                }
                __out(2, pending);
            }

            word main() {
                __setisr(&onIrq);
                __out(0, 2);                     // IRQMASK: the timer only
                __out(3, 12);                    // TMRPRELOAD: fire often
                __sti();
                for (word i = 0; i < COUNT; i = i + 1) {
                    mine[i] = __alloc(sizeof(struct Cell));
                    if (mine[i] == 0) { __cli(); return 9000; }
                }
                __cli();
                if (theirCount == 0) return 8000;   // the handler never ran
                for (word i = 0; i < COUNT; i = i + 1) {
                    for (word j = 0; j < theirCount; j = j + 1) {
                        if (mine[i] == theirs[j]) return 1000 + i;
                    }
                }
                return 0;
            }
            """;

    @Test
    @DisplayName("the race is real: both sides are handed the same block")
    void theRaceHappens() {
        // Pinned deliberately. If a future allocator makes this return 0 the hazard is
        // gone and the warning should go with it — but that is a decision to take
        // knowingly, not a test to quietly delete.
        Oracle.Result r = Oracle.get().run(Mona.compile(RACING).assembly(),
                500_000, null, null, null, 0, true);

        assertTrue(r.ok(), r::describe);
        assertTrue(r.a() >= 1000 && r.a() < 2000,
                () -> "expected an overlap at index " + (r.a() - 1000)
                        + "; got " + r.a() + "\n" + r.describe());
    }

    @Test
    @DisplayName("and the compiler says so before it is run")
    void theRaceIsReported() {
        String diagnostics = Mona.compile(RACING).diagnostics();
        assertTrue(diagnostics.contains("can run inside an interrupt handler"),
                () -> "the hazard should be reported\n" + diagnostics);
        assertTrue(diagnostics.contains("onIrq"),
                () -> "and it should name the handler\n" + diagnostics);
    }

    @Test
    @DisplayName("a handler that allocates alone is safe, because it cannot nest")
    void handlerOnlyIsQuiet() {
        // The CPU clears SR.irqMask on entering a handler and IRET restores it, so a
        // handler cannot interrupt itself. With nothing else allocating there is
        // nothing to race, and a warning here would be noise.
        String diagnostics = Mona.compile("""
                word* kept = 0;
                void onIrq() {
                    word pending = __in(1);
                    kept = __alloc(4);
                    __out(2, pending);
                }
                word main() {
                    __setisr(&onIrq);
                    __out(0, 2);
                    __out(3, 50);
                    __sti();
                    for (word i = 0; i < 200; i = i + 1) { }
                    __cli();
                    return kept == 0;
                }
                """).diagnostics();

        assertFalse(diagnostics.contains("interrupt handler"),
                () -> "only one side allocates; nothing to warn about\n" + diagnostics);
    }

    @Test
    @DisplayName("a program that allocates with no handler at all is quiet")
    void programOnlyIsQuiet() {
        String diagnostics = Mona.compile("""
                word main() {
                    word* p = __alloc(8);
                    if (p == 0) return 1;
                    __free(p);
                    return 0;
                }
                """).diagnostics();

        assertFalse(diagnostics.contains("interrupt handler"), diagnostics);
    }

    @Test
    @DisplayName("interrupts with no heap are quiet, which is most handlers")
    void interruptsWithoutHeapAreQuiet() {
        String diagnostics = Mona.compile("""
                word ticks = 0;
                void onIrq() {
                    word pending = __in(1);
                    ticks = ticks + 1;
                    __out(2, pending);
                }
                word main() {
                    __setisr(&onIrq);
                    __out(0, 2);
                    __out(3, 50);
                    __sti();
                    for (word i = 0; i < 200; i = i + 1) { }
                    __cli();
                    return ticks;
                }
                """).diagnostics();

        assertFalse(diagnostics.contains("interrupt handler"), diagnostics);
        assertEquals("", diagnostics.strip(), "and nothing else either");
    }
}
