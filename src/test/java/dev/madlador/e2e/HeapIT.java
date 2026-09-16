package dev.madlador.e2e;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.driver.Compiler;
import dev.madlador.driver.Options;
import dev.madlador.ir.HeapStrategy;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The heap allocator.
 *
 * <p>The heap is two bitmaps and the data they describe, one bit per two-byte unit
 * in each: {@code used} says whether a unit belongs to a live block, {@code start}
 * whether it is the first of one. Allocation is first fit — a run of free units is a
 * run of zero bits — and freeing clears bits and stops, because released space is
 * contiguous with its neighbours without anything being merged.
 *
 * <p>There are now three allocators — a bump pointer for a program that never frees,
 * fixed cells for one that allocates a single size, and the bitmap for everything
 * else — and this file is the contract all three answer to. So every behavioural test
 * runs under every one of them: {@code --heap-strategy} forces the choice, and a
 * program that cannot meet a tier's precondition falls back to the bitmap rather than
 * failing, which is why asking for all three of everything is safe.
 *
 * <p>That is the whole safety net for having three implementations of one contract.
 * They either agree, or this says which one does not.
 *
 * <p>The heap starts at a label emitted after all code and data, so the assembler
 * resolves it to the exact end of the image. With {@code --heap auto} the size is
 * worked out at startup as the distance from there to the stack reserve.
 */
@DisplayName("heap")
class HeapIT {

    private static final int STACK_TOP = 0x0FFF;

    /** The memory-mapped text display: thirty-two characters at 0x1000. */
    private static final int[] DISPLAY = {0x1000, 0x1020};

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** Compiles with a heap of the given size, or {@link Options#HEAP_AUTO}. */
    private static String compile(String source, int heapSize) {
        return compile(source, heapSize, null);
    }

    /** As above, with the allocator forced rather than chosen. */
    private static String compile(String source, int heapSize, HeapStrategy.Kind strategy) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result = new Compiler(file, reporter)
                .compile(Options.Stage.ASM, true, 1, heapSize, strategy);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return result.assembly();
    }

    private static Oracle.Result run(String source, int heapSize, HeapStrategy.Kind strategy) {
        return Oracle.get().run(compile(source, heapSize, strategy), 500_000);
    }

    /** As above, reading back the text display, for the cases that report a fault. */
    private static Oracle.Result runShowing(String source, HeapStrategy.Kind strategy) {
        return Oracle.get().run(compile(source, Options.HEAP_AUTO, strategy), 500_000, DISPLAY);
    }

    private static String screen(Oracle.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int b : result.memory()) sb.append(b == 0 ? ' ' : (char) b);
        return sb.toString().stripTrailing();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("an allocation is usable and distinct")
    void allocateAndWrite(HeapStrategy.Kind strategy) {
        Oracle.Result r = run("""
                word main() {
                    word* a = __alloc(4);
                    word* b = __alloc(4);
                    if (a == 0 || b == 0) return 1;
                    if (a == b) return 2;
                    a[0] = 111; a[1] = 222;
                    b[0] = 333; b[1] = 444;
                    // If the blocks overlapped, one pair would have been overwritten.
                    return a[0] + a[1] + b[0] + b[1];
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(1110, r.a(), r::describe);
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("a freed block is handed out again")
    void freeMakesSpaceReusable(HeapStrategy.Kind strategy) {
        Oracle.Result r = run("""
                word main() {
                    word* first = __alloc(8);
                    __free(first);
                    word* second = __alloc(8);
                    // First fit, so the same block comes straight back.
                    if (first == second) return 1;
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(1, r.a(), () -> "the freed block should be reused\n" + r.describe());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("adjacent free blocks merge, so a big request fits again")
    void freeCoalesces(HeapStrategy.Kind strategy) {
        // Three small blocks, all freed, then one request larger than any of them.
        // Without coalescing the heap would be fragmented and this would fail.
        Oracle.Result r = run("""
                word main() {
                    word* a = __alloc(20);
                    word* b = __alloc(20);
                    word* c = __alloc(20);
                    if (a == 0 || b == 0 || c == 0) return 1;
                    __free(b);
                    __free(a);
                    __free(c);
                    word* big = __alloc(60);
                    if (big == 0) return 2;
                    if (big != a) return 3;      // merged back into one run
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), () -> "blocks did not coalesce\n" + r.describe());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("running out of room returns zero rather than corrupting memory")
    void exhaustionReturnsZero(HeapStrategy.Kind strategy) {
        Oracle.Result r = run("""
                word main() {
                    // A deliberately tiny heap: the second request cannot fit.
                    word* a = __alloc(20);
                    word* b = __alloc(20);
                    if (a == 0) return 1;
                    if (b != 0) return 2;
                    return 0;
                }
                """, 32, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), () -> "a full heap must return 0\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("freeing a null pointer is allowed")
    void freeNullIsSafe(HeapStrategy.Kind strategy) {
        Oracle.Result r = run("""
                word main() {
                    __free(0);
                    word* a = __alloc(4);
                    if (a == 0) return 1;
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);
        assertEquals(0, r.a(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("many allocations and frees stay consistent")
    void repeatedUseIsStable(HeapStrategy.Kind strategy) {
        // Churn the heap, then check it can still satisfy a large request — which it
        // only can if every free coalesced properly.
        Oracle.Result r = run("""
                word main() {
                    for (word round = 0; round < 20; round += 1) {
                        word* a = __alloc(30);
                        word* b = __alloc(30);
                        if (a == 0 || b == 0) return 1;
                        __free(a);
                        __free(b);
                    }
                    word* big = __alloc(200);
                    if (big == 0) return 2;
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), () -> "the heap fragmented over time\n" + r.describe());
    }

    @Test
    @DisplayName("a heap appears because the program allocates, with no flag")
    void heapIsInferred() {
        // The need is obvious from the source, so requiring a flag would only be a
        // way to get it wrong.
        String allocating = compile("word main() { word* p = __alloc(4); return 0; }", 0);
        assertTrue(allocating.contains("rt_heap_base"),
                "a program that allocates should get a heap without being asked");
        assertTrue(allocating.contains("rt_alloc"), "and the allocator with it");

        Oracle.Result r = Oracle.get().run(allocating, 100_000);
        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), r::describe);
    }

    @Test
    @DisplayName("a program that never allocates carries no heap and no allocator")
    void payForWhatYouUse() {
        String plain = compile("word main() { return 1; }", 0);
        assertFalse(plain.contains("rt_alloc"), "no allocation, no allocator");
        assertFalse(plain.contains("rt_heap_base"), "no allocation, no heap region");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("a block spanning a bitmap word is allocated and freed whole")
    void blockCrossesAWordBoundary(HeapStrategy.Kind strategy) {
        // Sixteen units — 32 bytes — to a bitmap word, so a 40-byte block placed at
        // unit 4 runs past the end of the first word. Every loop in the allocator has
        // to carry the word address forward rather than assume one.
        Oracle.Result r = run("""
                word main() {
                    word* pad = __alloc(8);          // units 0..3
                    word* wide = __alloc(40);        // units 4..23, over the boundary
                    if (pad == 0 || wide == 0) return 1;
                    for (word i = 0; i < 20; i += 1) wide[i] = i + 1;
                    word sum = 0;
                    for (word i = 0; i < 20; i += 1) sum = sum + wide[i];
                    if (sum != 210) return 2;        // nothing overlapped it
                    __free(wide);
                    word* again = __alloc(40);
                    if (again != wide) return 3;     // and all twenty units came back
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), r::describe);
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("a run is found by crossing from a part-full word into an empty one")
    void runStartsInOneWordAndEndsInAnother(HeapStrategy.Kind strategy) {
        // The first block leaves the bitmap word part full; the second needs more
        // units than remain in it, so placing it means counting on into the word
        // after — the case the whole-word shortcut must not swallow.
        Oracle.Result r = run("""
                word main() {
                    word* head = __alloc(24);        // units 0..11
                    word* wide = __alloc(24);        // units 12..23, straddling
                    if (head == 0 || wide == 0) return 1;
                    if (wide != head + 12) return 2; // placed immediately after
                    wide[0] = 7; wide[11] = 9;
                    if (wide[0] + wide[11] != 16) return 3;
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("freeing forwards and backwards both give the whole run back")
    void freeOrderDoesNotMatter(HeapStrategy.Kind strategy) {
        // Clearing bits is order-independent by construction. A free list had to
        // merge with the block before and the block after, and the order in which
        // its neighbours were released decided whether it got both.
        String program = """
                word main() {
                    word* a = __alloc(20);
                    word* b = __alloc(20);
                    word* c = __alloc(20);
                    if (a == 0 || b == 0 || c == 0) return 1;
                    %s
                    word* big = __alloc(60);
                    if (big == 0) return 2;
                    if (big != a) return 3;
                    return 0;
                }
                """;

        Oracle.Result up = run(program.formatted("__free(a); __free(b); __free(c);"),
                Options.HEAP_AUTO, strategy);
        assertTrue(up.ok(), up::describe);
        assertEquals(0, up.a(), () -> "ascending frees left a gap\n" + up.describe());

        Oracle.Result down = run(program.formatted("__free(c); __free(b); __free(a);"),
                Options.HEAP_AUTO, strategy);
        assertTrue(down.ok(), down::describe);
        assertEquals(0, down.a(), () -> "descending frees left a gap\n" + down.describe());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("a request larger than the heap returns zero rather than looping")
    void oversizedRequestFails(HeapStrategy.Kind strategy) {
        Oracle.Result r = run("""
                word main() {
                    word* huge = __alloc(4000);      // larger than the machine
                    if (huge != 0) return 1;
                    word* small = __alloc(4);        // and the heap still works
                    if (small == 0) return 2;
                    return 0;
                }
                """, Options.HEAP_AUTO, strategy);

        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "the scan should end, not spin\n" + r.describe());
        assertEquals(0, r.a(), r::describe);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("freeing something that never came from the heap says so and stops")
    void freeingALocalIsCaught(HeapStrategy.Kind strategy) {
        // The old allocator read a size out of whatever happened to precede the
        // pointer and walked off into the program. Three checks — inside the data
        // area, even, and actually the start of a block — turn that into a message.
        Oracle.Result r = runShowing("""
                word main() {
                    word local = 5;
                    word* p = __alloc(4);            // so there is a heap at all
                    if (p == 0) return 1;
                    __free(&local);                  // not ours
                    return 0;
                }
                """, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals("HEAP CORRUPT", screen(r),
                () -> "the display should say what went wrong\n" + r.describe());
        assertTrue(r.halted(), () -> "it should stop, not run on\n" + r.describe());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("freeing the same block twice says so and stops")
    void doubleFreeIsCaught(HeapStrategy.Kind strategy) {
        // The reason the slab spends a bit per cell rather than threading a free list
        // through the cells themselves. A list would be a pop and a push — faster than
        // anything here — but freeing twice would just corrupt it, quietly, which is
        // where a good deal of C's exploitable memory corruption comes from.
        Oracle.Result r = runShowing("""
                word main() {
                    word* p = __alloc(4);
                    if (p == 0) return 1;
                    __free(p);
                    __free(p);                       // and again
                    return 0;
                }
                """, strategy);

        assertTrue(r.ok(), r::describe);
        assertEquals("HEAP CORRUPT", screen(r),
                () -> "a double free should be caught\n" + r.describe());
        assertTrue(r.halted(), () -> "it should stop, not run on\n" + r.describe());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HeapStrategy.Kind.class)
    @DisplayName("the worked example runs")
    void exampleRuns(HeapStrategy.Kind strategy) {
        Path source = Path.of("examples/4-data/heap.mona");
        assumeTrue(Files.isReadable(source), "examples/4-data/heap.mona is missing");
        String program;
        try {
            program = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Oracle.Result r = run(program, Options.HEAP_AUTO, strategy);
        assertTrue(r.ok(), r::describe);
        // 1..10 summed is 55, then everything freed and one node of 100 added.
        assertEquals(155, r.a(), r::describe);
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }
}
