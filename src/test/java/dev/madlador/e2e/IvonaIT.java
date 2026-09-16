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
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The IVONA banner, checked by looking at the pixels it actually drew.
 *
 * <p>Like the cube, and for the same reason: asserting an exact bitmap would break on
 * any harmless change, so these check the properties a correct banner has.
 *
 * <p>Two of them are about the thing that makes this program different from the cube.
 * It never clears the screen: a letter is rubbed out by drawing its own strokes again
 * in colour 0, and only the letter whose second is up is touched at all. That buys
 * the animation its speed and costs it any margin for error, because both halves of
 * the trick have to be exactly right.
 *
 * <ul>
 *   <li>{@link #erasingLeavesNothingBehind()} — if erasing missed even one pixel of
 *       one stroke, the count would climb every second the program ran. Running it
 *       for a hundred replacements and finding the same count as the first frame is
 *       what says the erase is exact.
 *   <li>{@link #staysOnScreen()} — nothing clips. {@code line} walks a pixel address,
 *       so a coordinate that left the screen would not fault; it would wrap and draw
 *       a stripe somewhere else. What keeps it correct is the arithmetic staying
 *       inside 0..255, and that is an argument in a comment until something runs it.
 * </ul>
 */
@DisplayName("ivona")
class IvonaIT {

    /** The whole framebuffer: 256 by 256, one byte per pixel. */
    private static final int[] VRAM = {0, 65536};

    private static String source;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
        Path file = Path.of("examples/3-graphics/ivona.mona");
        assumeTrue(Files.isReadable(file), "examples/3-graphics/ivona.mona is missing");
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Runs the endless program for a budget of instructions and reads the screen. */
    private static Oracle.Result frames(int steps) {
        return Oracle.get().run(Mona.compile(source).assembly(), steps,
                null, VRAM, null, 0, true);
    }

    /**
     * The same program, stopped when no letter is halfway through being replaced.
     *
     * <p>A step budget cannot promise that. Replacing a letter erases it and draws it
     * again, so an arbitrary instant has a one-in-five chance of catching a letter
     * that is currently nowhere. Halting at the top of the main loop instead — where
     * every letter has just been left in one piece — gives a settled picture.
     *
     * @param seconds how long to let it run first, in the program's own seconds
     */
    private static Oracle.Result settled(int seconds) {
        String mark = "        // Every letter is whole here; the tests stop the program at this line.\n";
        assertTrue(source.contains(mark), "the example's main loop moved");
        String bounded = source.replace(mark,
                mark + "        if (ticks > " + seconds + " * TICKS_PER_SECOND) return 0;\n");
        Oracle.Result r = Oracle.get().run(Mona.compile(bounded).assembly(),
                4_000_000, null, VRAM, null, 0, true);
        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "the bounded copy should stop\n" + r.describe());
        return r;
    }

    private static int litPixels(Oracle.Result result) {
        int lit = 0;
        for (int pixel : result.videoMemory()) if (pixel != 0) lit++;
        return lit;
    }

    /** Left, top, right, bottom of everything drawn, or nulls when nothing was. */
    private static int[] bounds(Oracle.Result result) {
        int[] vram = result.videoMemory();
        int left = 256, top = 256, right = -1, bottom = -1;
        for (int address = 0; address < vram.length; address++) {
            if (vram[address] == 0) continue;
            int x = address & 0xFF;
            int y = address >> 8;
            if (x < left) left = x;
            if (x > right) right = x;
            if (y < top) top = y;
            if (y > bottom) bottom = y;
        }
        return new int[]{left, top, right, bottom};
    }

    @Test
    @DisplayName("a frame draws the word, and nothing faults")
    void drawsTheWord() {
        Oracle.Result r = frames(120_000);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "no port or memory access should fault\n" + r.describe());
        assertTrue(r.timedOut(), () -> "the program runs forever by design\n" + r.describe());

        int lit = litPixels(r);
        assertTrue(lit > 250 && lit < 900,
                () -> "nineteen strokes should be a few hundred pixels, got " + lit);
    }

    @Test
    @DisplayName("erasing leaves nothing behind, however long it runs")
    void erasingLeavesNothingBehind() {
        // The whole design rests on this. Nothing is ever cleared, so a stroke that
        // the erase missed would stay lit for the rest of the run, and the count
        // would climb second by second.
        int atFirst = litPixels(settled(1));
        int later = litPixels(settled(20));
        assertTrue(atFirst > 250,
                () -> "the sign should be drawn after a second, got " + atFirst);
        assertEquals(atFirst, later,
                "erasing is not exact: after twenty seconds the screen holds a"
                        + " different number of pixels than after one");
    }

    @Test
    @DisplayName("everything it draws is on the screen")
    void staysOnScreen() {
        // Run long enough to pass the crest and the trough of the swell, so the
        // largest and smallest displacements both get drawn.
        Oracle.Result r = frames(1_200_000);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);

        int[] box = bounds(r);
        assertTrue(box[2] >= 0, () -> "nothing was drawn at all\n" + r.describe());
        // A wrapped coordinate shows up as a stray pixel far from the word, so a tight
        // box is the evidence that every coordinate stayed in range.
        assertTrue(box[0] >= 8 && box[2] <= 247,
                () -> "drawn outside the screen horizontally: " + box[0] + ".." + box[2]);
        assertTrue(box[1] >= 60 && box[3] <= 200,
                () -> "drawn outside the expected band vertically: " + box[1] + ".." + box[3]);
    }

    @Test
    @DisplayName("it is five letters, not one blob")
    void fiveSeparateLetters() {
        Oracle.Result r = settled(3);
        int[] vram = r.videoMemory();

        // Every column that has any pixel in it. The five letter boxes are 24 wide on
        // a 46 pitch, so the gaps between them are empty columns.
        boolean[] used = new boolean[256];
        for (int address = 0; address < vram.length; address++) {
            if (vram[address] != 0) used[address & 0xFF] = true;
        }
        int counted = 0;
        for (int x = 1; x < 256; x++) {
            if (used[x] && !used[x - 1]) counted++;
        }
        final int runs = counted;
        assertTrue(runs == 5,
                () -> "expected five letters separated by gaps, found " + runs + " groups");
    }

    @Test
    @DisplayName("the letters are not all the same colour")
    void theColoursTravel() {
        Oracle.Result r = settled(3);
        Set<Integer> palette = new HashSet<>();
        for (int pixel : r.videoMemory()) if (pixel != 0) palette.add(pixel);
        assertTrue(palette.size() >= 3,
                () -> "the ramp should put several colours on screen at once, got " + palette);
    }

    @Test
    @DisplayName("it moves")
    void theWaveTravels() {
        Oracle.Result early = settled(1);
        Oracle.Result later = settled(9);

        int counted = 0;
        int[] a = early.videoMemory();
        int[] b = later.videoMemory();
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) counted++;
        final int differing = counted;
        assertTrue(differing > 100,
                () -> "the word should have moved between the two frames, " + differing
                        + " pixels differ");
    }

    @Test
    @DisplayName("it fits, with room for the stack")
    void itFits() {
        int bytes = Mona.imageBytes(source);
        assertTrue(bytes < 3400, () -> "the image is " + bytes + " bytes of 4096");
    }
}
