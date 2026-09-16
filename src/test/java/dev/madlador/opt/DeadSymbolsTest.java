package dev.madlador.opt;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What survives into the image, and what does not.
 *
 * <p>These matter more than they would on a larger machine. The program, its data and
 * its stack share 4 KB, so a function that is written but never reached is not merely
 * untidy — it is stack the program does not get.
 */
class DeadSymbolsTest {

    private static String compile(String source) {
        return Mona.compile(source, 1).assembly();
    }

    @Test
    @DisplayName("a function nothing calls is not emitted")
    void unreachableFunctionIsDropped() {
        String assembly = compile("""
                word helper(word a) { return a * 3; }
                word main() { return 1; }
                """);
        assertFalse(assembly.contains("m_helper"),
                () -> "an uncalled function should not reach the image:\n" + assembly);
    }

    @Test
    @DisplayName("dropping a function drops what only it used")
    void deadFunctionTakesItsDataWithIt() {
        String assembly = compile("""
                word g_only_helper_reads = 1234;
                word g_main_reads = 5;
                word helper() { return g_only_helper_reads; }
                word main() { return g_main_reads; }
                """);
        assertFalse(assembly.contains("only_helper_reads"),
                () -> "a global read only by dead code should go too:\n" + assembly);
        assertTrue(assembly.contains("main_reads"),
                () -> "the live global must stay:\n" + assembly);
    }

    @Test
    @DisplayName("reachability is transitive, in both directions")
    void reachabilityIsTransitive() {
        String assembly = compile("""
                word deep(word a) { return a + 1; }
                word middle(word a) { return deep(a) + 1; }
                word dead_middle(word a) { return dead_deep(a); }
                word dead_deep(word a) { return a; }
                word main() { return middle(1); }
                """);
        assertTrue(assembly.contains("m_deep"),
                () -> "a function reached through another is live:\n" + assembly);
        assertFalse(assembly.contains("m_dead_deep"),
                () -> "a function reached only from dead code is dead:\n" + assembly);
    }

    @Test
    @DisplayName("an interrupt handler is kept although nothing calls it")
    void handlerInstalledByAddressSurvives() {
        // __setisr stores the handler's address; the call comes from the trampoline,
        // which is not in the IR at all. Reachability has to follow &onKey.
        String assembly = compile("""
                word g_hits = 0;
                void onKey() { g_hits = g_hits + 1; }
                word main() {
                    __setisr(&onKey);
                    return g_hits;
                }
                """);
        assertTrue(assembly.contains("m_onKey"),
                () -> "the handler must survive being installed by address:\n" + assembly);
    }

    @Test
    @DisplayName("an unreachable allocation costs neither allocator nor heap")
    void deadAllocationLeavesNoHeap() {
        String assembly = compile("""
                word dead_allocate(word n) {
                    word* p = __alloc(n);
                    __free(p);
                    return 1;
                }
                word main() { return 7; }
                """);
        assertFalse(assembly.contains("rt_alloc"),
                () -> "the allocator should not be emitted:\n" + assembly);
        assertFalse(assembly.contains("rt_heap"),
                () -> "no heap should be reserved either:\n" + assembly);
    }

    @Test
    @DisplayName("a call the optimizer removes takes its callee with it")
    void foldingCanExposeADeadFunction() {
        String assembly = compile("""
                word never_runs(word n) { return n * 3; }
                word main() {
                    if (0) { return never_runs(5); }
                    return 1;
                }
                """);
        assertFalse(assembly.contains("m_never_runs"),
                () -> "the only call was in a block folding removed:\n" + assembly);
    }

    @Test
    @DisplayName("--no-entry keeps everything, because there is no program to reach from")
    void bareCompilationKeepsEverything() {
        String assembly = Mona.compileBare("""
                word helper(word a) { return a * 3; }
                word main() { return 1; }
                """).assembly();
        assertTrue(assembly.contains("m_helper"),
                () -> "bare functions are the deliverable, not dead code:\n" + assembly);
    }

    @Test
    @DisplayName("nothing is dropped at -O0")
    void unoptimizedKeepsEverything() {
        String assembly = Mona.compile("""
                word helper(word a) { return a * 3; }
                word main() { return 1; }
                """, 0).assembly();
        assertTrue(assembly.contains("m_helper"),
                () -> "-O0 should be the obvious translation of what was written:\n" + assembly);
    }
}
