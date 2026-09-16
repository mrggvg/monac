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
 * Programs that drive the machine's peripherals through {@code __in} and
 * {@code __out}.
 *
 * <p>The graphics card, keypad, timer and random generator live in a port space that
 * is separate from memory and unreachable by any pointer, so these two builtins are
 * the only way a Mona program can touch them. Video memory is checked directly,
 * which makes "did it draw the right thing" an assertion rather than something to
 * squint at in a browser.
 */
@DisplayName("graphics and port I/O")
class GraphicsIT {

    /** Tile memory starts at VRAM 0, two bytes per tile: character then colour. */
    private static int[] tiles(int count) {
        return new int[]{0, count * 2};
    }

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    @Test
    @DisplayName("a program can write tiles to video memory")
    void writesTiles() {
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                void setTile(word x, word y, byte ch, byte colour) {
                    __out(8, (y * 128 + x) * 2);
                    __out(9, ch * 256 + colour);
                }
                word main() {
                    __out(7, 1);
                    setTile(0, 0, 'M', 15);
                    setTile(1, 0, 'O', 14);
                    setTile(2, 0, 'N', 13);
                    setTile(3, 0, 'A', 12);
                    return 4;
                }
                """).assembly(), 100_000, null, tiles(4));

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);

        int[] vram = r.videoMemory();
        assertEquals('M', vram[0]);
        assertEquals(15, vram[1]);
        assertEquals('O', vram[2]);
        assertEquals(14, vram[3]);
        assertEquals('N', vram[4]);
        assertEquals('A', vram[6]);
        assertEquals(12, vram[7]);
    }

    @Test
    @DisplayName("a port can be read back")
    void readsPorts() {
        // VIDADDR latches an address and reloads VIDDATA from it, so writing a cell
        // and then re-latching should read the same word back.
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word main() {
                    __out(7, 1);
                    __out(8, 0);
                    __out(9, 16706);
                    __out(8, 0);
                    return __in(9);
                }
                """).assembly(), 100_000, null, tiles(1));

        assertTrue(r.ok(), r::describe);
        assertEquals(16706, r.a(), r::describe);
    }

    @Test
    @DisplayName("the random generator returns varying values")
    void randomGenerator() {
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word main() {
                    word differences = 0;
                    word previous = __in(10);
                    for (word i = 0; i < 20; i += 1) {
                        word next = __in(10);
                        if (next != previous) differences += 1;
                        previous = next;
                    }
                    return differences;
                }
                """).assembly(), 100_000, null, tiles(1));

        assertTrue(r.ok(), r::describe);
        // Twenty draws from a 16-bit generator; all-equal would mean it is not one.
        assertTrue(r.a() > 15, () -> "expected varying values, got " + r.a() + " changes");
    }

    @Test
    @DisplayName("a port number may be computed rather than literal")
    void computedPortNumber() {
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word main() {
                    word videoMode = 3 + 4;      // port 7
                    __out(videoMode, 1);
                    __out(videoMode + 1, 0);     // port 8
                    __out(videoMode + 2, 16961); // port 9
                    return 0;
                }
                """).assembly(), 100_000, null, tiles(1));

        assertTrue(r.ok(), r::describe);
        assertEquals('B', r.videoMemory()[0], r::describe);
        assertEquals('A', r.videoMemory()[1], r::describe);
    }

    @Test
    @DisplayName("snake compiles, fits, loads its artwork and paints the board")
    void snakeRuns() {
        Path source = Path.of("examples/5-games/snake.mona");
        assumeTrue(Files.isReadable(source), "examples/5-games/snake.mona is missing");

        String program;
        try {
            program = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Mona.Compiled compiled = Mona.compile(program);
        assertTrue(compiled.diagnostics().isEmpty(),
                () -> "snake should compile clean:\n" + compiled.diagnostics());

        // It paces on the graphics card's 50 Hz refresh, which is wall-clock and
        // driven by a JavaScript interval, so it cannot reach a game over under the
        // oracle's synchronous run loop -- the wait never returns. Playing it through
        // needs src/test/resources/probe/vsync.js, which runs the CPU in slices and
        // lets the event loop turn; it halts there with the score in A.
        //
        // What this test can do is prove the setup ran: the artwork reached the card
        // and the first board was painted before the first wait.
        Oracle.Result r = Oracle.get().run(compiled.assembly(), 400_000,
                null, new int[]{0x9DA0, 0x9DC0}, null, 0, true);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "snake faulted\n" + r.describe());

        // The image and the stack share 4 KB, so this is the real constraint.
        assertTrue(r.imageSize() < 3800,
                () -> "snake is " + r.imageSize() + " bytes, leaving too little for the stack");

        // Tile 0xED is the head facing up, and its eleventh and twelfth bytes are the
        // top of the solid green skull. If the artwork never crossed into the card
        // this range is still zero.
        int[] vram = r.videoMemory();
        int nonZero = 0;
        for (int byteValue : vram) if (byteValue != 0) nonZero++;
        final int lit = nonZero;
        assertTrue(lit > 8,
                () -> "tile 0xED looks empty, so the artwork never reached the card: " + lit);

        Oracle.Result painted = Oracle.get().runScreen(compiled.assembly(), 400_000,
                112, 176, 16, 16);
        assertTrue(painted.ok(), painted::describe);
        int[] head = painted.screen();
        int greenCount = 0;
        int whiteCount = 0;
        for (int pixel : head) {
            if (pixel == 0x24B400) greenCount++;
            if (pixel == 0xFFFFFF) whiteCount++;
        }
        final int green = greenCount;
        final int white = whiteCount;
        assertTrue(green > 100, () -> "the head should be mostly green, got " + green);
        assertTrue(white >= 16, () -> "it should have white eyes, got " + white);
    }
}
