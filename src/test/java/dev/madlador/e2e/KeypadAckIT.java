package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Why a keypad handler must read {@code KBDDATA} <em>before</em> it acknowledges.
 *
 * <p>The keypad is the only <b>level</b>-triggered device on this machine. Pressing a
 * key raises both the controller's level bit and its status bit for line 0. Reading
 * {@code KBDDATA} lowers the <em>level</em> — and only the level; the status bit stays
 * set. Writing {@code IRQEOI} clears the status bit — except that {@code IRQEOI} is
 * {@code status ^= value} followed by {@code status |= level}, so it puts back
 * anything the level still asserts.
 *
 * <p>Both steps are therefore needed, in that order, and getting it wrong fails in two
 * different ways — neither of which looks like an interrupt bug:
 *
 * <ul>
 *   <li>Acknowledge <em>then</em> read, and the level is still high at the
 *       {@code IRQEOI}, so the status bit is immediately restored and the handler runs
 *       a second time for one keypress. Nothing hangs and nothing faults; a counter
 *       kept in the handler is simply twice what it should be, which reads as "the
 *       snake moves two squares per press".</li>
 *   <li>Never read {@code KBDDATA} at all and the level never falls, so every
 *       acknowledgement re-raises the request and the program makes no further
 *       progress.</li>
 * </ul>
 *
 * <p>{@code examples/2-hardware/interrupt.mona} has the order right. These tests are here so
 * that stays true, and so the reason is written down somewhere.
 */
@DisplayName("keypad interrupts: read the data, then acknowledge")
class KeypadAckIT {

    /** A handler body around one statement order, plus a spin long enough to be hit. */
    private static String program(String handlerBody) {
        return """
                word entries = 0;
                word sawKey = 0;
                void onIrq() {
                    word pending = __in(1);         // IRQSTATUS
                %s
                    entries = entries + 1;
                }
                word main() {
                    __setisr(&onIrq);
                    __out(0, 1);                    // IRQMASK: the keypad
                    __sti();
                    word spins = 0;
                    while (spins < 30000) { spins = spins + 1; }
                    __cli();
                    return entries;
                }
                """.formatted(handlerBody);
    }

    private static Oracle.Result runWithOneKey(String source, int maxSteps) {
        return Oracle.get().run(Mona.compile(source).assembly(), maxSteps,
                null, null, "A", 500);
    }

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    @Test
    @DisplayName("reading KBDDATA before IRQEOI delivers one keypress once")
    void readThenAcknowledge() {
        Oracle.Result r = runWithOneKey(program("""
                    if (pending & 1) {
                        sawKey = __in(6);           // KBDDATA: this lowers the level
                    }
                    __out(2, pending);              // and now the status bit clears
                """), 3_000_000);

        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "it should have finished: " + r.describe());
        assertEquals(1, r.registers().get("A"), "one key, one entry");
    }

    @Test
    @DisplayName("acknowledging first delivers one keypress twice, silently")
    void acknowledgeThenReadRunsTwice() {
        Oracle.Result r = runWithOneKey(program("""
                    __out(2, pending);              // the level is still high here
                    if (pending & 1) {
                        sawKey = __in(6);           // so the IRQEOI above put it back
                    }
                """), 3_000_000);

        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "it still finishes, which is what makes it nasty: "
                + r.describe());
        assertEquals(2, r.registers().get("A"),
                "one key should be counted twice with the acknowledgement first");
    }

    @Test
    @DisplayName("never reading KBDDATA leaves the request raised and the program stuck")
    void neverReadingTheDataLivelocks() {
        Oracle.Result r = runWithOneKey(program("""
                    __out(2, pending);              // acknowledged, but never read
                """), 1_000_000);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.halted(),
                () -> "with the level never lowered this cannot finish: " + r.describe());
        assertTrue(r.timedOut(), "it should exhaust the step budget");
    }

    @Test
    @DisplayName("the worked example gets the order right")
    void theExampleIsCorrect() {
        // If someone reorders these two lines in the example, this fails rather than
        // the example quietly starting to double-count.
        Path file = Path.of("examples/2-hardware/interrupt.mona");
        assumeTrue(Files.isReadable(file), "examples/2-hardware/interrupt.mona is missing");
        String example;
        try {
            example = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int readData = example.indexOf("__in(6)");
        int acknowledge = example.indexOf("__out(2,");
        assertTrue(readData > 0, "the example should read KBDDATA");
        assertTrue(acknowledge > 0, "and acknowledge");
        assertTrue(readData < acknowledge,
                "interrupt.mona must read KBDDATA before it writes IRQEOI");
    }

    @Test
    @DisplayName("KBDSTATUS is a bit field, and polling code has to drain KBDDATA")
    void pollingMustDrainOnEveryEvent() {
        // A real keypress delivers TWO events -- 1 down, 2 up -- and 4 is OR-ed in
        // when a previous one was never collected. So the values a program sees are
        // 1, 2, 5 and 6, never just 0 and 1.
        //
        // Reading KBDDATA is what returns the port to 0. Code that tests for 1 and
        // returns early therefore never drains a key-up: the port latches at 2, the
        // next press reads 5 rather than 1, and the keyboard is dead from then on.
        // This shipped in snake.mona, and nothing could see it because the oracle
        // only ever sent key-downs.
        String buggy = """
                word seen = 0;
                word main() {
                    word spins = 0;
                    while (spins < 12000) {
                        if (__in(5) == 1) {         // the bug: only ever drains on 1
                            word k = __in(6);
                            seen = seen + 1;
                        }
                        spins = spins + 1;
                    }
                    return seen;
                }
                """;
        String correct = """
                word seen = 0;
                word main() {
                    word spins = 0;
                    while (spins < 12000) {
                        word status = __in(5);
                        if (status != 0) {
                            word k = __in(6);       // drain first, whatever it was
                            if ((status & 1) != 0) seen = seen + 1;
                        }
                        spins = spins + 1;
                    }
                    return seen;
                }
                """;

        Oracle.Result jammed = Oracle.get().runWithKeyUp(
                Mona.compile(buggy).assembly(), 300_000, "abc", 9000);
        assertTrue(jammed.ok(), jammed::describe);
        assertEquals(1, jammed.registers().get("A"),
                "the first press is seen and then the port latches on its release");

        Oracle.Result drained = Oracle.get().runWithKeyUp(
                Mona.compile(correct).assembly(), 300_000, "abc", 9000);
        assertTrue(drained.ok(), drained::describe);
        assertEquals(3, drained.registers().get("A"),
                "draining on every event keeps all three presses visible");
    }

    @Test
    @DisplayName("snake drains the keypad on a release, so it keeps steering")
    void snakePollsCorrectly() {
        Path file = Path.of("examples/5-games/snake.mona");
        assumeTrue(Files.isReadable(file), "examples/5-games/snake.mona is missing");
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The shape matters more than any run: KBDDATA must be read before the code
        // decides whether the event was a press, or the port latches on the release.
        int status = source.indexOf("__in(KBDSTATUS)");
        int data = source.indexOf("__in(KBDDATA)");
        int downTest = source.indexOf("& KEY_DOWN");
        assertTrue(status > 0 && data > 0, "snake should poll both keypad ports");
        assertTrue(data < downTest,
                "snake must drain KBDDATA before testing whether the key was going down");
        assertTrue(!source.contains("__in(KBDSTATUS) != 1"),
                "testing KBDSTATUS against 1 misses key-up and latches the port");
    }

    @Test
    @DisplayName("__getkey drains on every event, so the port never latches")
    void getkeyDrainsEveryEvent() {
        // The builtin exists to make the drain unskippable. Three presses, each
        // pressed and released, is three downs and three ups -- and the old idiom
        // (test the status against 1, leave early) sees one down and then nothing.
        Oracle.Result r = Oracle.get().runWithKeyUp(Mona.compile("""
                word main() {
                    word downs = 0;
                    word ups = 0;
                    word spins = 0;
                    while (spins < 12000) {
                        word k = __getkey();
                        if (k != 0) {
                            word status = k >> 8;
                            if ((status & 1) != 0) downs = downs + 1;
                            if ((status & 2) != 0) ups = ups + 1;
                        }
                        spins = spins + 1;
                    }
                    return downs * 10 + ups;
                }
                """).assembly(), 900_000, "abc", 9000);

        assertTrue(r.ok(), r::describe);
        assertEquals(33, r.registers().get("A"),
                () -> "expected three downs and three ups: " + r.describe());
    }

    @Test
    @DisplayName("__getkey returns the key in its low byte, and 0 when nothing waits")
    void getkeyCarriesBothHalves() {
        Oracle.Result r = Oracle.get().runWithKeyUp(Mona.compile("""
                word main() {
                    word idle = __getkey();     // before any key: nothing waiting
                    if (idle != 0) return 1;
                    word seen = 0;
                    word spins = 0;
                    while (spins < 12000) {
                        word k = __getkey();
                        // 'z' going down is status 1, key 122.
                        if (k == 256 + 122) seen = 1;
                        spins = spins + 1;
                    }
                    if (seen == 0) return 2;
                    return 0;
                }
                """).assembly(), 900_000, "z", 3000);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"), r::describe);
    }
}
