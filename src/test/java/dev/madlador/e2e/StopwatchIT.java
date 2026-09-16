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
 * The worked stopwatch, run on the real machine with the timer and keypad attached.
 *
 * <p>Timing is exact rather than approximate, which is the useful thing about a
 * machine where every instruction costs one clock tick. Writing {@code TMRPRELOAD}
 * loads the counter and takes one tick to start, then the counter falls by one per
 * instruction and raises IRQ 1 when it reaches zero; the reload costs another tick.
 * So interrupts arrive every {@code preload + 1} instructions, and the program's ten
 * of those make a second — {@link #INSTRUCTIONS_PER_SECOND} of them.
 *
 * <p>The program never halts, which is what a stopwatch does. Each run is bounded by
 * a step budget and the display is read back out of memory afterwards.
 */
@DisplayName("stopwatch")
class StopwatchIT {

    /** The text display: thirty-two characters at 0x1000. */
    private static final int[] DISPLAY = {0x1000, 0x1020};

    /** Matches TIMER_PRELOAD and TICKS_PER_SECOND in the program. */
    private static final int INSTRUCTIONS_PER_SECOND = (1000 + 1) * 10;

    private static String source;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
        Path file = Path.of("examples/2-hardware/stopwatch.mona");
        assumeTrue(Files.isReadable(file), "examples/2-hardware/stopwatch.mona is missing");
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Oracle.Result run(int steps, String keys, int keyEvery) {
        return Oracle.get().run(Mona.compile(source).assembly(), steps,
                DISPLAY, null, keys, keyEvery, true);
    }

    /** The display as text, trailing blanks and NULs trimmed. */
    private static String screen(Oracle.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int b : result.memory()) sb.append(b == 0 ? ' ' : (char) b);
        return sb.toString().stripTrailing();
    }

    /** The HH:MM:SS field, as a number of seconds. */
    private static int elapsed(Oracle.Result result) {
        String text = screen(result);
        int hours = Integer.parseInt(text.substring(10, 12));
        int minutes = Integer.parseInt(text.substring(13, 15));
        int seconds = Integer.parseInt(text.substring(16, 18));
        return hours * 3600 + minutes * 60 + seconds;
    }

    @Test
    @DisplayName("it draws a clock before the first tick")
    void startsAtZero() {
        // Few enough steps that no second can have elapsed, so this is the frame and
        // the initial render and nothing else.
        Oracle.Result r = run(2_000, null, 0);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "no port should fault\n" + r.describe());
        assertEquals("STOPWATCH 00:00:00 SPACE=RESET", screen(r),
                () -> "the whole display, drawn once at startup\n" + r.describe());
    }

    @Test
    @DisplayName("the timer advances it by the right number of seconds")
    void countsSeconds() {
        // Five seconds of instructions, plus the startup that runs before the timer
        // is even started — so five is the floor and six the ceiling.
        Oracle.Result r = run(5 * INSTRUCTIONS_PER_SECOND, null, 0);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);
        assertTrue(r.timedOut(), () -> "a stopwatch does not halt\n" + r.describe());

        int seconds = elapsed(r);
        assertTrue(seconds >= 4 && seconds <= 5,
                () -> "expected about five seconds, read " + seconds + "\n" + screen(r));
    }

    @Test
    @DisplayName("it keeps counting, and the minutes roll over")
    void rollsOverIntoMinutes() {
        // Just past a minute, so the seconds column has to have wrapped and carried.
        Oracle.Result r = run(63 * INSTRUCTIONS_PER_SECOND, null, 0);
        assertTrue(r.ok(), r::describe);

        String text = screen(r);
        assertTrue(text.startsWith("STOPWATCH 00:01:0"),
                () -> "expected a minute and a little, read " + text);
        int seconds = elapsed(r);
        assertTrue(seconds >= 62 && seconds <= 63,
                () -> "expected about 63 seconds, read " + seconds + "\n" + text);
    }

    @Test
    @DisplayName("space resets it")
    void spaceResets() {
        // One space, pressed after roughly eight seconds of a ten second run. The
        // reset lands in the keyboard half of the same handler that serves the
        // timer, so this also proves the two devices do not shut each other out.
        int steps = 10 * INSTRUCTIONS_PER_SECOND;
        Oracle.Result r = run(steps, " ", (int) (steps * 0.8));
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);

        int seconds = elapsed(r);
        assertTrue(seconds <= 3,
                () -> "the count should have restarted, read " + seconds + "\n" + screen(r));
    }

    @Test
    @DisplayName("a key that is not space is ignored")
    void otherKeysDoNotReset() {
        int steps = 10 * INSTRUCTIONS_PER_SECOND;
        Oracle.Result r = run(steps, "x", (int) (steps * 0.8));
        assertTrue(r.ok(), r::describe);

        int seconds = elapsed(r);
        assertTrue(seconds >= 8,
                () -> "only space resets, read " + seconds + "\n" + screen(r));
    }

    @Test
    @DisplayName("it resets again after being reset")
    void resetsRepeatedly() {
        int steps = 12 * INSTRUCTIONS_PER_SECOND;
        Oracle.Result r = run(steps, "  ", 4 * INSTRUCTIONS_PER_SECOND);
        assertTrue(r.ok(), r::describe);

        // Pressed at four and eight seconds, so about four seconds are left to show.
        int seconds = elapsed(r);
        assertTrue(seconds >= 3 && seconds <= 5,
                () -> "expected about four seconds since the last reset, read "
                        + seconds + "\n" + screen(r));
    }
}
