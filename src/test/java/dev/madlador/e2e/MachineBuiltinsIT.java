package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The five builtins that exist for this machine rather than for the language.
 *
 * <p>{@code __vwrite}, {@code __vread} and {@code __vfill} move blocks between RAM and
 * the graphics card's 64 KB. There is no DMA — ports 0 to 10 are the whole machine —
 * so this is a loop through {@code VIDADDR}/{@code VIDDATA} either way, and the loop
 * is not the same in both video modes: a {@code VIDDATA} write stores two bytes in
 * tile mode and one in bitmap. The builtins read {@code VIDMODE} and branch, so a byte
 * count means a byte count regardless, which is the property most of these tests are
 * about.
 *
 * <p>{@code __waitframe} paces on the card's 50 Hz refresh by polling, so it needs no
 * handler at all. Only its display-off path can be tested here; the waiting itself
 * cannot, because the refresh is driven by a JavaScript interval and the oracle's run
 * loop is synchronous. {@code src/test/resources/probe/} has the hand-run version.
 *
 * <p>{@code __mulhi} recovers the half of a product that {@code MUL} throws away.
 */
@DisplayName("machine builtins: video blocks, frame pacing, the high half of a product")
class MachineBuiltinsIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** Runs a program with the peripherals attached and insists it reported success. */
    private static Oracle.Result ok(String source) {
        Oracle.Result r = Oracle.get().run(
                Mona.compile(source).assembly(), 500_000, null, null, null, 0, true);
        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"),
                () -> "the program reported failure " + r.registers().get("A") + "\n" + r.describe());
        return r;
    }

    @Test
    @DisplayName("a block goes out to video memory and comes back byte for byte, in tile mode")
    void roundTripInTileMode() {
        // Tile mode moves two bytes a write, and RAM is big-endian the same way round,
        // so a word passes through untouched. Eight bytes is four writes.
        ok("""
                byte buffer[8];
                byte back[8];
                word main() {
                    __out(7, 1);
                    for (word i = 0; i < 8; i += 1) buffer[i] = i + 1;
                    __vwrite(60000, buffer, 8);
                    __vread(60000, back, 8);
                    for (word i = 0; i < 8; i += 1) {
                        if (back[i] != i + 1) return 1 + i;
                    }
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("an odd byte count in tile mode leaves the next byte alone")
    void oddCountDoesNotDisturbTheNeighbour() {
        // This is the whole reason the builtin is byte-counted rather than
        // write-counted. A lone trailing byte cannot be written on its own -- one
        // VIDDATA write always stores two in tile mode -- so the tail reads the pair
        // back, replaces one half, and writes it again.
        ok("""
                byte buffer[4];
                byte back[4];
                word main() {
                    __out(7, 1);
                    buffer[0] = 1; buffer[1] = 2; buffer[2] = 3; buffer[3] = 9;
                    __vfill(60016, 0, 4);           // a known-clear run
                    __vwrite(60016, buffer, 3);     // three bytes, not four
                    __vread(60016, back, 4);
                    if (back[0] != 1) return 1;
                    if (back[1] != 2) return 2;
                    if (back[2] != 3) return 3;
                    if (back[3] != 0) return 4;     // untouched, and not buffer[3]
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("a fill writes whole cells in tile mode: high byte tile, low byte colour")
    void fillWritesCellsInTileMode() {
        ok("""
                byte back[6];
                word main() {
                    __out(7, 1);
                    __vfill(60032, 4386, 6);        // 0x1122, three cells
                    __vread(60032, back, 6);
                    if (back[0] != 17) return 1;    // 0x11
                    if (back[1] != 34) return 2;    // 0x22
                    if (back[4] != 17) return 3;
                    if (back[5] != 34) return 4;
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("in bitmap mode the same calls move single bytes, and draw")
    void bitmapModeMovesBytes() {
        // Here video memory IS the screen, so the round trip is also a check that the
        // right pixels were set. Row 0 gets four palette indices out of a RAM buffer.
        Oracle.Result r = Oracle.get().runScreen(Mona.compile("""
                byte buffer[4];
                byte back[4];
                word main() {
                    __out(7, 2);
                    buffer[0] = 224; buffer[1] = 28; buffer[2] = 3; buffer[3] = 255;
                    __vwrite(0, buffer, 4);
                    __vread(0, back, 4);
                    for (word i = 0; i < 4; i += 1) {
                        if (back[i] != buffer[i]) return 1 + i;
                    }
                    // No tail case exists in bitmap mode: every write is one byte, so
                    // an odd count is not special.
                    __vfill(1024, 0, 4);
                    __vfill(1024, 224, 3);
                    __vread(1024, back, 4);
                    if (back[2] != 224) return 10;
                    if (back[3] != 0) return 11;
                    return 0;
                }
                """).assembly(), 500_000, 0, 0, 5, 1);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"), r::describe);
        int[] p = r.screen();
        assertEquals(0xFC0000, p[0], "index 224 is red");
        assertEquals(0x00FC00, p[1], "index 28 is green");
        assertEquals(0x0000FF, p[2], "index 3 is blue");
        assertEquals(0xFFFFFF, p[3], "index 255 is white");
        assertEquals(0x000000, p[4], "and the run stopped where it was told to");
    }

    @Test
    @DisplayName("a zero count moves nothing rather than wrapping to 65536")
    void zeroCountIsANoOp() {
        ok("""
                byte buffer[2];
                byte back[2];
                word main() {
                    __out(7, 1);
                    __vfill(60048, 0, 2);
                    buffer[0] = 7; buffer[1] = 8;
                    __vwrite(60048, buffer, 0);
                    __vfill(60048, 65535, 0);
                    __vread(60048, back, 2);
                    if (back[0] != 0) return 1;
                    if (back[1] != 0) return 2;
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("__waitframe returns at once when the display is off, instead of hanging")
    void waitFrameWithNoDisplay() {
        // With no display there is no refresh, so a wait would never end. The waiting
        // itself needs the JavaScript event loop to turn and so cannot be tested here
        // at all -- see src/test/resources/probe/vsync.js, which measures 48 Hz.
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word main() {
                    word frames = 0;
                    while (frames < 25) {           // VIDMODE never set
                        __waitframe();
                        frames += 1;
                    }
                    return frames;
                }
                """).assembly(), 200_000, null, null, null, 0, true);

        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "it should have finished, not spun: " + r.describe());
        assertEquals(25, r.registers().get("A"));
    }

    @Test
    @DisplayName("__mulhi agrees with exact arithmetic over 1,344 pairs")
    void mulhiIsExact() {
        // MUL is 16x16 into 16, so the high half is rebuilt from four byte-wide
        // products and the carries between them. Two of those carries are invisible
        // unless you look for them: the middle sum al*bh + ah*bl reaches 130,050 and
        // overflows a word -- where one bit of overflow is worth 256, not 1 -- and the
        // low half of the product can carry up into the high half.
        //
        // So: every crossing of the interesting boundaries, plus twelve hundred random
        // pairs from a fixed seed. Batched because the reference has to be computed in
        // Java, which means the pairs are baked into the source, and 4 KB does not
        // hold fourteen hundred comparisons.
        int[] edges = {0, 1, 2, 255, 256, 257, 4095, 32767, 32768, 32769, 65534, 65535};
        List<int[]> pairs = new ArrayList<>();
        for (int a : edges) {
            for (int b : edges) pairs.add(new int[]{a, b});
        }
        Random random = new Random(20260910L);
        for (int i = 0; i < 1200; i++) {
            pairs.add(new int[]{random.nextInt(65536), random.nextInt(65536)});
        }

        final int perBatch = 90;
        for (int start = 0; start < pairs.size(); start += perBatch) {
            List<int[]> batch = pairs.subList(start, Math.min(start + perBatch, pairs.size()));
            StringBuilder source = new StringBuilder("word main() {\n");
            for (int i = 0; i < batch.size(); i++) {
                int a = batch.get(i)[0];
                int b = batch.get(i)[1];
                int high = (a * b) >>> 16;
                source.append("    if (__mulhi(").append(a).append(", ").append(b)
                        .append(") != ").append(high).append(") return ").append(i + 1).append(";\n");
            }
            source.append("    return 0;\n}\n");

            Oracle.Result r = Oracle.get().run(Mona.compile(source.toString()).assembly(), 9_000_000);
            assertTrue(r.ok(), r::describe);
            int failed = r.registers().get("A");
            if (failed != 0) {
                int[] pair = batch.get(failed - 1);
                assertEquals(0, failed, "__mulhi(" + pair[0] + ", " + pair[1] + ") should be "
                        + ((pair[0] * pair[1]) >>> 16));
            }
        }
    }

    @Test
    @DisplayName("the fixed-point recipe in the docs is exact")
    void documentedFixedPointRecipeIsExact() {
        // language.md tells people to combine the halves like this for scale 256. If it
        // is wrong, it is wrong in every program that copies it.
        StringBuilder source = new StringBuilder(
                "word fixmul(word a, word b) { return (__mulhi(a, b) << 8) | ((a * b) >> 8); }\n"
                        + "word main() {\n");
        Random random = new Random(7L);
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            pairs.add(new int[]{random.nextInt(65536), random.nextInt(65536)});
        }
        for (int[] pair : new int[][]{{256, 256}, {65535, 65535}, {1000, 1000},
                                      {0, 12345}, {1, 65535}, {32768, 512}, {255, 255}}) {
            pairs.add(pair);
        }
        for (int i = 0; i < pairs.size(); i++) {
            int a = pairs.get(i)[0];
            int b = pairs.get(i)[1];
            int expected = (int) ((((long) a * b) >> 8) & 0xFFFF);
            source.append("    if (fixmul(").append(a).append(", ").append(b)
                    .append(") != ").append(expected).append(") return ").append(i + 1).append(";\n");
        }
        source.append("    return 0;\n}\n");

        Oracle.Result r = Oracle.get().run(Mona.compile(source.toString()).assembly(), 9_000_000);
        assertTrue(r.ok(), r::describe);
        int failed = r.registers().get("A");
        if (failed != 0) {
            int[] pair = pairs.get(failed - 1);
            assertEquals(0, failed, "the recipe is wrong for " + pair[0] + " * " + pair[1]);
        }
    }

    @Test
    @DisplayName("__halt stops the machine where it stands")
    void haltStopsTheMachine() {
        // It leaves whatever was last in A, so the assertion is on memory instead: the
        // text display sits at a known address, 0x1000, and is ordinary RAM.
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word main() {
                    byte* screen = 4096;        // 0x1000
                    screen[0] = 'A';
                    __halt();
                    screen[0] = 'B';            // must never run
                    return 0;
                }
                """).assembly(), 100_000, new int[]{0x1000, 0x1001});

        assertTrue(r.ok(), r::describe);
        assertTrue(r.halted(), () -> "it should have stopped: " + r.describe());
        assertEquals('A', r.memory()[0],
                "the store after __halt must never have happened");
    }

    @Test
    @DisplayName("__ticks counts down once per instruction, and the count is real")
    void ticksMeasuresInstructions() {
        // TMRCOUNTER only moves once the timer has a preload, and it counts DOWN, so
        // the earlier reading is the larger one. Doing four times the work must cost
        // more than doing it once -- that is the property worth pinning, rather than
        // any particular number.
        Oracle.Result r = Oracle.get().run(Mona.compile("""
                word spin(word times) {
                    word i = 0;
                    while (i < times) i = i + 1;
                    return i;
                }
                word main() {
                    __out(3, 30000);
                    word b1 = __ticks(); spin(50);  word a1 = __ticks();
                    word b2 = __ticks(); spin(200); word a2 = __ticks();
                    word short1 = b1 - a1;
                    word long1 = b2 - a2;
                    if (short1 == 0) return 1;
                    if (long1 <= short1) return 2;
                    // Four times the loop, so within a factor of two of four times the
                    // cost -- loose, because the calls and reads are fixed overhead.
                    if (long1 < short1 * 2) return 3;
                    return 0;
                }
                """).assembly(), 500_000, null, null, null, 0, true);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.registers().get("A"), r::describe);
    }

    @Test
    @DisplayName("a program that uses none of them carries none of them")
    void unusedHelpersAreNotEmitted() {
        // The whole argument for adding these is that they cost nothing when unused.
        String assembly = Mona.compile("word main() { return 7; }").assembly();
        for (String helper : new String[]{"rt_vwrite", "rt_vread", "rt_vfill",
                                          "rt_waitframe", "rt_mulhi", "rt_getkey"}) {
            assertTrue(!assembly.contains(helper),
                    () -> helper + " was emitted into a program that never calls it");
        }
        // And one that uses exactly one carries exactly one.
        String one = Mona.compile("word main() { return __mulhi(1000, 1000); }").assembly();
        assertTrue(one.contains("rt_mulhi"), "the helper it does use must be there");
        assertTrue(!one.contains("rt_vwrite"), "but not the ones it does not");
    }
}
