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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Drawing with the tile ROM instead of uploading tiles.
 *
 * <p>Four CP437 glyphs — blank, upper half, lower half and solid — are exactly a
 * two-bit lookup, so every cell is two independently settable square pixels with its
 * own colour. That makes the tile map a 128&times;256 grid of them, scrollable a pixel
 * at a time, for no tile definitions at all.
 *
 * <p>These assert on rendered pixels rather than on the map, because the whole claim is
 * about what the ROM draws — reading back the tile indices a program wrote would only
 * prove it stored the numbers it meant to.
 */
@DisplayName("block glyphs: drawing with tiles nobody uploaded")
class BlockGlyphsIT {

    /** The sky, the two greens, the earth and the rock, as the 3-3-2 cube renders them. */
    private static final int SKY = 0x4890FF;
    private static final int GRASS = 0x24FC00;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    @Test
    @DisplayName("the four glyphs give a cell two independent halves")
    void halvesAreIndependent() {
        // The point of the whole technique, checked one cell at a time: the upper-half
        // glyph must light the top eight rows and leave the bottom eight, and the
        // lower-half glyph the reverse. If these two ever overlap or gap, the grid is
        // not a grid.
        Oracle.Result r = Oracle.get().runScreen(Mona.compile("""
                void setCell(word x, word y, word tile, word colour) {
                    __out(8, (y * 128 + x) * 2);
                    __out(9, tile * 256 + colour);
                }
                word main() {
                    __out(7, 1);
                    setCell(0, 0, 223, 255);    // 0xDF upper half, white
                    setCell(1, 0, 220, 255);    // 0xDC lower half, white
                    setCell(2, 0, 219, 255);    // 0xDB solid
                    return 0;
                }
                """).assembly(), 200_000, 0, 0, 48, 16);

        assertTrue(r.ok(), r::describe);
        int[] p = r.screen();
        // Row 0 of the upper-half cell is lit; row 8 is not.
        assertEquals(0xFFFFFF, p[0], "upper half lights its top row");
        assertEquals(0x000000, p[48 * 8], "and leaves its bottom row alone");
        // The lower-half cell is the exact complement.
        assertEquals(0x000000, p[16], "lower half leaves its top row");
        assertEquals(0xFFFFFF, p[48 * 8 + 16], "and lights its bottom row");
        // Solid is both.
        assertEquals(0xFFFFFF, p[32], "solid lights the top");
        assertEquals(0xFFFFFF, p[48 * 8 + 32], "and the bottom");
    }

    @Test
    @DisplayName("blocks.mona draws a landscape without uploading a single tile")
    void theExampleDrawsTerrain() {
        Path file = Path.of("examples/3-graphics/blocks.mona");
        assumeTrue(Files.isReadable(file), "examples/3-graphics/blocks.mona is missing");
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Mona.Compiled compiled = Mona.compile(source);
        assertTrue(compiled.diagnostics().isEmpty(),
                () -> "the example should compile clean:\n" + compiled.diagnostics());
        // No __vwrite, no defineTile: the artwork is four constants.
        assertTrue(!source.contains("__vwrite"),
                "the point of this example is that it uploads no tile data");

        // It paces on the card's refresh, so it cannot run to completion here -- but
        // the terrain is drawn before the first wait.
        Oracle.Result r = Oracle.get().runScreen(compiled.assembly(), 400_000, 0, 0, 256, 256);
        assertTrue(r.ok(), r::describe);

        int sky = 0;
        int grass = 0;
        int lit = 0;
        for (int pixel : r.screen()) {
            if (pixel == SKY) sky++;
            else if (pixel != 0x000000) lit++;
            if (pixel == GRASS) grass++;
        }
        final int skyCount = sky;
        final int litCount = lit;
        final int grassCount = grass;
        assertTrue(skyCount > 10_000,
                () -> "most of the screen should be sky, got " + skyCount);
        assertTrue(litCount > 5_000,
                () -> "and a good deal of it terrain, got " + litCount);
        assertTrue(grassCount > 500,
                () -> "with a grass line along the top of it, got " + grassCount);

        // A landscape, not a wall: the topmost non-sky pixel has to vary across the
        // screen, or the random walk did not walk.
        int[] screen = r.screen();
        int lowest = 256;
        int highest = 0;
        for (int x = 0; x < 256; x += 16) {
            int y = 0;
            while (y < 256 && screen[256 * y + x] == SKY) y++;
            lowest = Math.min(lowest, y);
            highest = Math.max(highest, y);
        }
        final int spread = highest - lowest;
        assertTrue(spread >= 16,
                () -> "the terrain should undulate, but its profile spans " + spread + " pixels");
    }
}
