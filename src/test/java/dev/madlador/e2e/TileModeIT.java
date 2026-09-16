package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What the graphics card can actually do in tile mode, driven from Mona.
 *
 * <p>VIDMODE 1 is called "text mode" in the simulator, and the name undersells it by
 * a wide margin. It is a tile engine: a 128&times;128 map of 16&times;16 tiles — 2048
 * pixels square — seen through a 256-pixel window that scrolls over it a pixel at a
 * time, with 256 redefinable tile shapes, a programmable 256-entry palette, a
 * background colour and eight sprites. None of that appears in
 * {@link GraphicsIT}, which only ever wrote tile indices into the map.
 *
 * <p>These tests assert on the <em>rendered canvas</em> rather than on video memory,
 * through {@link Oracle#runScreen}. That distinction is the whole point: reading the
 * tile map back only proves a program stored what it meant to store. Whether the card
 * composed a scroll offset, a tile definition, a palette entry and a sprite into the
 * right pixels is a different question, and it is the one that matters.
 *
 * <p>Colours below come from the card's palette ROM, which is a 3-3-2 RGB cube with
 * the channels stepped by 36, 36 and 85 — so index 224 is (252,&nbsp;0,&nbsp;0) and
 * not (255,&nbsp;0,&nbsp;0). Index 255 is the one exception, special-cased to pure
 * white.
 */
@DisplayName("tile mode: scrolling, sprites and redefinable tiles")
class TileModeIT {

    private static final int WHITE = 0xFFFFFF;   // palette index 255
    private static final int RED = 0xFC0000;     // index 224: r=7
    private static final int GREEN = 0x00FC00;   // index 28:  g=7
    private static final int BLUE = 0x0000FF;    // index 3:   b=3

    /** 0x8000 + 32 * n — where tile n's 32 bytes of 1-bit-per-pixel shape live. */
    private static int tileDef(int n) {
        return 0x8000 + 32 * n;
    }

    /**
     * A Mona helper every program here needs: fill one tile definition with a repeated
     * 16-bit row. A VIDDATA write in tile mode stores two bytes, so a 32-byte
     * definition is sixteen writes.
     */
    private static final String DEFINE_TILE = """
            void defineTile(word which, word row) {
                word at = 32768 + which * 32;
                word end = at + 32;
                while (at < end) {
                    __out(8, at);
                    __out(9, row);
                    at += 2;
                }
            }
            void setCell(word x, word y, word tile, word colour) {
                __out(8, (y * 128 + x) * 2);
                __out(9, tile * 256 + colour);
            }
            """;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    private static Oracle.Result screen(String body, int x, int y, int w, int h) {
        Oracle.Result r = Oracle.get().runScreen(
                Mona.compile(DEFINE_TILE + body).assembly(), 200_000, x, y, w, h);
        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"), "the program itself reported a failure");
        return r;
    }

    @Test
    @DisplayName("a tile definition written from Mona is what gets drawn")
    void redefinableTiles() {
        // Tile 1 solid, tile 3 left-half-only. Neither shape exists in the tile ROM,
        // so if these pixels are right the card is drawing what the program defined.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);       // 0xFFFF: every pixel set
                    defineTile(3, 65280);       // 0xFF00: left eight of each row
                    setCell(0, 0, 1, 255);      // white
                    setCell(1, 0, 3, 224);      // red
                    return 0;
                }
                """, 0, 0, 32, 1);
        int[] p = r.screen();
        assertEquals(WHITE, p[0], "tile 1 is solid, so its first pixel is lit");
        assertEquals(WHITE, p[15], "and so is its last");
        assertEquals(RED, p[16], "tile 3's left half is lit");
        assertEquals(RED, p[23], "all eight pixels of it");
        assertEquals(0, p[24], "its right half is clear, so the background shows");
        assertEquals(0, p[31], "for the remaining eight");
    }

    @Test
    @DisplayName("HScroll and VScroll slide the window a pixel at a time")
    void pixelGranularScrolling() {
        // The window is 16 tiles wide over a map 128 tiles wide, and the scroll
        // registers are in pixels, not tiles: 8 moves everything half a tile.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);
                    setCell(0, 0, 1, 255);
                    setCell(1, 0, 1, 224);
                    __out(8, 41730);            // 0xA302: HScroll, big-endian word
                    __out(9, 8);
                    return 0;
                }
                """, 0, 0, 32, 1);
        int[] p = r.screen();
        assertEquals(WHITE, p[0], "the first tile has slid eight pixels off the left");
        assertEquals(WHITE, p[7], "so only its right half is on screen");
        assertEquals(RED, p[8], "and the second tile starts eight pixels early");
        assertEquals(RED, p[23], "running a full sixteen from there");
    }

    @Test
    @DisplayName("scrolling reaches the far end of a map far bigger than the screen")
    void scrollingReachesTheWholeMap() {
        // Tile (100, 60) is 1600 pixels right and 960 down: nowhere near the window
        // until it is scrolled to. The registers clamp at 1792, which is exactly
        // 2048 - 256, so the last tile of the map can be brought fully into view.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);
                    setCell(100, 60, 1, 28);    // green, far off screen
                    __out(8, 41730); __out(9, 1600);    // HScroll
                    __out(8, 41732); __out(9, 960);     // VScroll
                    return 0;
                }
                """, 0, 0, 17, 1);
        int[] p = r.screen();
        assertEquals(GREEN, p[0], "the distant tile is now at the window's origin");
        assertEquals(GREEN, p[15], "all sixteen pixels of it");
        assertEquals(0, p[16], "and the next cell is empty");
    }

    @Test
    @DisplayName("eight sprites draw over the tiles, and their clear pixels are transparent")
    void sprites() {
        // A sprite is four bytes at 0xA306 + 4n: tile, colour, x, y. It is in screen
        // space, so the scroll does not move it -- which is what makes it useful.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);       // solid
                    defineTile(2, 65280);       // left half only
                    __out(8, 41728); __out(9, 3);       // 0xA301: background = blue

                    __out(8, 41734); __out(9, 1 * 256 + 28);    // sprite 1: solid, green
                    __out(8, 41736); __out(9, 100 * 256 + 50);  // at (100, 50)

                    __out(8, 41738); __out(9, 2 * 256 + 224);   // sprite 2: half, red
                    __out(8, 41740); __out(9, 200 * 256 + 50);  // at (200, 50)
                    return 0;
                }
                """, 100, 50, 116, 1);
        int[] p = r.screen();
        assertEquals(GREEN, p[0], "sprite 1 begins exactly at its x");
        assertEquals(GREEN, p[15], "and is sixteen pixels wide");
        assertEquals(BLUE, p[16], "past it, the background shows through");
        assertEquals(RED, p[100], "sprite 2 begins at 200");
        assertEquals(RED, p[107], "for the eight pixels its shape lights");
        assertEquals(BLUE, p[108], "and the rest of its box is transparent, not black");
    }

    @Test
    @DisplayName("a sprite with tile index 0 is skipped, so 0 is how you hide one")
    void spriteZeroIsOff() {
        // Tile 0 is defined solid here to make the check unambiguous, and it has a
        // second consequence worth knowing: every map cell a program has not written
        // is (tile 0, colour 0), so redefining tile 0 paints the whole map in palette
        // entry 0. The background colour then never shows anywhere.
        //
        // Against that black map, a sprite in green would be obvious. It is skipped,
        // so it is not there.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(0, 65535);
                    __out(8, 41728); __out(9, 3);               // background = blue
                    __out(8, 41734); __out(9, 0 * 256 + 28);    // sprite 1: tile 0, green
                    __out(8, 41736); __out(9, 100 * 256 + 50);
                    return 0;
                }
                """, 100, 50, 4, 1);
        for (int pixel : r.screen()) {
            assertEquals(0x000000, pixel,
                    "tile 0 covers the map in palette entry 0, and the sprite drew nothing");
            assertNotEquals(GREEN, pixel);
        }
    }

    @Test
    @DisplayName("the palette is programmable in tile mode")
    void programmablePalette() {
        // Entry n is three bytes at 0xA000 + 3n. Two word writes cover one entry and
        // spill one byte into the next entry's red, so entry 3 is collateral here and
        // deliberately unused.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);
                    __out(8, 40966); __out(9, 16512);   // 0xA006: entry 2 R=0x40 G=0x80
                    __out(8, 40968); __out(9, 49152);   // 0xA008: entry 2 B=0xC0
                    setCell(0, 0, 1, 2);
                    return 0;
                }
                """, 0, 0, 1, 1);
        assertEquals(0x4080C0, r.screen()[0],
                "the tile should be drawn in the colour the program put in the palette");
    }

    @Test
    @DisplayName("but not in bitmap mode, which reads the palette ROM instead")
    void bitmapModePaletteIsFixed() {
        // The same write in bitmap mode changes nothing, because repaintBitmapScreen
        // looks colours up in the card's ROM and never in video memory. It is worth a
        // test because the two modes look like they share a palette and do not.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 2);
                    __out(8, 40966); __out(9, 16512);   // would be entry 2, in tile mode
                    __out(8, 40968); __out(9, 49152);
                    __out(8, 0); __out(9, 2);           // pixel (0,0) = palette index 2
                    return 0;
                }
                """, 0, 0, 1, 1);
        assertEquals(0x0000AA, r.screen()[0],
                "index 2 is ROM (0, 0, 170): b=2 of the 3-3-2 cube, stepped by 85");
        assertNotEquals(0x4080C0, r.screen()[0]);
    }

    @Test
    @DisplayName("VIDMODE 3 in tile mode clears the map and keeps everything else")
    void clearPreservesDefinitions() {
        // It clears the first 32 KB, which is exactly the tile map -- the definitions,
        // the palette, the scroll registers and the sprites all live above it and
        // survive. In bitmap mode the same 3 clears all 64 KB.
        Oracle.Result r = screen("""
                word main() {
                    __out(7, 1);
                    defineTile(1, 65535);
                    __out(8, 41728); __out(9, 3);       // background = blue
                    setCell(0, 0, 1, 255);
                    __out(7, 3);                        // clear
                    setCell(1, 0, 1, 255);              // place it again, next cell over
                    return 0;
                }
                """, 0, 0, 32, 1);
        int[] p = r.screen();
        assertEquals(BLUE, p[0], "the cell written before the clear is gone");
        assertEquals(WHITE, p[16], "but the definition survived, so the new cell draws");
        assertEquals(WHITE, p[31], "solid, as defined");
    }

    @Test
    @DisplayName("video memory can be read back, which makes it 23 KB of scratch space")
    void videoMemoryIsReadable() {
        // Writing VIDADDR preloads VIDDATA from that address, so a read is two
        // instructions. In tile mode nothing above 0xA326 is rendered, which leaves
        // 23,754 bytes the card is not using -- five times the machine's whole RAM.
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word peek(word at) {
                    __out(8, at);
                    return __in(9);
                }
                void poke(word at, word value) {
                    __out(8, at);
                    __out(9, value);
                }
                word main() {
                    __out(7, 1);
                    poke(60000, 4660);              // 0x1234
                    poke(60002, 43981);             // 0xABCD
                    if (peek(60000) != 4660) return 1;
                    if (peek(60002) != 43981) return 2;
                    // A word read is big-endian across the two bytes, so an odd
                    // address straddles them: 0x34 0xAB.
                    if (peek(60001) != 13483) return 3;
                    return 0;
                }
                """).assembly(), 200_000, null, new int[]{60000, 60004});

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"), r::describe);
        assertArrayEqualsish(new int[]{0x12, 0x34, 0xAB, 0xCD}, r.videoMemory());
    }

    private static void assertArrayEqualsish(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length, "wrong amount of video memory read back");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "video memory byte " + i);
        }
    }

    @Test
    @DisplayName("tile definitions land where the layout says they do")
    void tileDefinitionAddresses() {
        assertEquals(0x8000, tileDef(0));
        assertEquals(0x8020, tileDef(1));
        assertEquals(0x9FE0, tileDef(255), "the 256th definition ends the 8 KB block");
    }
}
