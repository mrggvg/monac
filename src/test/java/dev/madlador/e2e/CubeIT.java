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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rotating cube, checked by looking at the pixels it actually drew.
 *
 * <p>The interesting thing about this program is that it is the first one where the
 * machine's limits are the design. There is no floating point, so the coordinates are
 * fixed point at a scale of 128 — and 128 rather than 256 because {@code MUL} is 16
 * by 16 into 16 bits, and a rotation's largest intermediate is sqrt(2) times the
 * scale squared, which at 256 would wrap.
 *
 * <p>Rather than assert an exact bitmap, which would break on any harmless change,
 * these check the properties a correct cube has: it is drawn, it is inside the
 * screen, its far face is smaller than its near face, and it changes as it turns.
 */
@DisplayName("cube")
class CubeIT {

    /** The whole framebuffer: 256 by 256, one byte per pixel. */
    private static final int[] VRAM = {0, 65536};

    private static String source;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
        Path file = Path.of("examples/3-graphics/cube.mona");
        assumeTrue(Files.isReadable(file), "examples/3-graphics/cube.mona is missing");
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

    private static int litPixels(Oracle.Result result) {
        int lit = 0;
        for (int pixel : result.videoMemory()) if (pixel != 0) lit++;
        return lit;
    }

    /** The bounding box of what was drawn, as {minX, minY, maxX, maxY}. */
    private static int[] bounds(Oracle.Result result) {
        int[] vram = result.videoMemory();
        int minX = 256, minY = 256, maxX = -1, maxY = -1;
        for (int i = 0; i < vram.length; i++) {
            if (vram[i] == 0) continue;
            int x = i % 256;
            int y = i / 256;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new int[] {minX, minY, maxX, maxY};
    }

    /**
     * A completed frame, found rather than assumed.
     *
     * <p>The program never stops and clears the screen at the top of every frame, so
     * a run cut off at a fixed instruction count can land anywhere — including one
     * instruction after a clear, on a blank screen. Rather than pin a step budget to
     * whatever a frame costs today, which any change to the compiler would move, this
     * samples across a window wider than a frame and keeps the fullest picture. The
     * fullest is the most complete: the drawing only ever adds.
     */
    private static Oracle.Result completeFrameWithin(int from, int to) {
        Oracle.Result best = null;
        int most = -1;
        for (int i = 0; i < 8; i++) {
            Oracle.Result r = frames(from + (to - from) * i / 7);
            int lit = litPixels(r);
            if (lit > most) {
                most = lit;
                best = r;
            }
        }
        return best;
    }

    /**
     * What a frame costs: 13,958 instructions, measured by running the program for four
     * frames and for eight and taking the difference.
     *
     * <p>The checks below care which <em>angle</em> they look at, so the windows are
     * placed by frame number from this rather than pinned to step counts. They used to
     * be pinned, written when a frame cost 26,500; each time the compiler made a frame
     * cheaper the same step count landed on a later angle, until the "about frame
     * seven" window drifted to the quarter turn, where the silhouette narrows again,
     * and the rotation check failed on a picture that was pixel for pixel right. When a
     * frame's cost moves a long way, this is the number to update.
     */
    private static final int FRAME = 13_958;

    /**
     * The fullest picture of frame {@code k}, sampled across its last stretch and just
     * past it, since the next frame begins by clearing the screen.
     */
    private static Oracle.Result frame(int k) {
        return completeFrameWithin(k * FRAME + FRAME * 17 / 20, k * FRAME + FRAME * 23 / 20);
    }

    /** Frame zero: the cube at angle zero, face on. */
    private static Oracle.Result firstFrame() {
        if (first == null) first = frame(0);
        return first;
    }

    /** Far enough in to be several turns later: frame seven. */
    private static Oracle.Result laterFrame() {
        if (later == null) later = frame(7);
        return later;
    }

    private static Oracle.Result first;
    private static Oracle.Result later;

    @Test
    @DisplayName("a frame draws a cube, and nothing faults")
    void drawsACube() {
        Oracle.Result r = firstFrame();
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "no port or memory access should fault\n" + r.describe());
        assertTrue(r.timedOut(), () -> "the program runs forever by design\n" + r.describe());

        int lit = litPixels(r);
        // Twelve edges of an eighty-pixel cube, with corners shared.
        assertTrue(lit > 400 && lit < 1600,
                () -> "expected a wireframe, got " + lit + " lit pixels");
    }

    @Test
    @DisplayName("everything it draws is on the screen")
    void staysOnScreen() {
        // The projection has no clipping, on purpose: the geometry cannot leave the
        // screen. This is the check that says so, and it is worth having because
        // video memory is a separate space where an overrun would be silent.
        Oracle.Result r = firstFrame();
        int[] box = bounds(r);
        assertTrue(box[0] >= 8 && box[1] >= 8 && box[2] <= 247 && box[3] <= 247,
                () -> "the cube should sit well inside the screen, drew "
                        + box[0] + ".." + box[2] + " by " + box[1] + ".." + box[3]);
    }

    @Test
    @DisplayName("perspective makes the far face smaller than the near one")
    void perspectiveShrinksTheFarFace() {
        // At angle zero the two faces are square and concentric, so counting lit
        // pixels per row finds them: the outer square's rows are wide, the inner
        // square's are narrow. Without perspective the far face would be hidden
        // exactly behind the near one and the picture would be a single square.
        Oracle.Result r = firstFrame();
        int[] vram = r.videoMemory();
        int[] box = bounds(r);

        int middle = (box[1] + box[3]) / 2;
        int nearWidth = box[2] - box[0];
        int innerLeft = -1;
        int innerRight = -1;
        for (int x = box[0] + 4; x <= box[2] - 4; x++) {
            if (vram[middle * 256 + x] != 0) {
                if (innerLeft < 0) innerLeft = x;
                innerRight = x;
            }
        }
        assertTrue(innerLeft > 0 && innerRight > innerLeft,
                () -> "expected a second square inside the first");
        int farWidth = innerRight - innerLeft;
        assertTrue(farWidth < nearWidth * 3 / 4,
                () -> "the far face should be clearly smaller: " + farWidth
                        + " against " + nearWidth);
    }

    @Test
    @DisplayName("it turns")
    void theCubeRotates() {
        // The first frame against one about seven turns of the wheel later.
        Oracle.Result start = firstFrame();
        Oracle.Result turned = laterFrame();
        assertTrue(turned.ok(), turned::describe);
        assertFalse(turned.fault(), turned::describe);

        int[] a = start.videoMemory();
        int[] b = turned.videoMemory();
        int changed = 0;
        for (int i = 0; i < a.length; i++) if ((a[i] == 0) != (b[i] == 0)) changed++;
        final int differing = changed;
        assertTrue(differing > 200,
                () -> "the picture should have changed as it rotated, "
                        + differing + " pixels differ");

        // A rotated cube is wider than a face-on one: at zero degrees the silhouette
        // is the near square, and turning it brings a second face into view.
        assertTrue(bounds(turned)[2] - bounds(turned)[0] > bounds(start)[2] - bounds(start)[0],
                "turning the cube should widen its silhouette");
    }

    @Test
    @DisplayName("it fits, with room for the stack")
    void itFits() {
        // Not a formality on a machine where the program, its data and the stack
        // share 4096 bytes.
        int bytes = Mona.imageBytes(source);
        assertTrue(bytes < 3000,
                () -> "the cube should leave over a kilobyte for the stack, took " + bytes);
    }
}
