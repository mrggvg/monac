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
 * How big the compiler thinks the image is, against how big it actually is.
 *
 * <p>The figure decides whether the program is rejected for not fitting in 4 KB and
 * how much of what is left the heap may have, so being wrong about it is not cosmetic.
 * It was wrong for a long time in a way nothing noticed: runtime helpers are appended
 * as text rather than as structured instructions, and the text was worth zero bytes.
 * A program carrying the allocator was reported at 331 bytes when the assembler
 * produced 1,089, and the fit check could not have fired for it.
 *
 * <p>So each case here is a family of code the estimate has to know about — helpers,
 * data directives, string literals, byte operations — and the assertion is exact
 * equality with the simulator's own assembler rather than a bound.
 */
@DisplayName("image size")
class ImageSizeIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** Fails unless the compiler's byte count is exactly the assembler's. */
    private static void assertExact(String what, String source) {
        String assembly = Mona.compile(source).assembly();
        Oracle.Result r = Oracle.get().assemble(assembly);
        assertEquals(r.imageSize(), Mona.imageBytes(source),
                () -> "the estimate for " + what + " is off\n" + r.describe());
    }

    @Test
    @DisplayName("a program with no runtime helpers at all")
    void plainCode() {
        assertExact("plain code", """
                word add(word a, word b) { return a + b; }
                word main() {
                    word total = 0;
                    for (word i = 0; i < 10; i += 1) total = add(total, i);
                    return total;
                }
                """);
    }

    @Test
    @DisplayName("the allocator, which is the largest helper")
    void heapHelpers() {
        assertExact("the allocator", """
                word main() {
                    word* p = __alloc(8);
                    if (p == 0) return 1;
                    __free(p);
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("the signed-arithmetic helpers")
    void signedHelpers() {
        assertExact("signed arithmetic", """
                sword main() {
                    sword a = 0 - 17;
                    sword b = 5;
                    return a / b + a % b;
                }
                """);
    }

    @Test
    @DisplayName("the stack guard, whose reporter is a string and byte operations")
    void stackGuard() {
        // .rt_report copies with MOVB and compares with CMPB, whose immediate is one
        // byte rather than two — the last thing the estimate was getting wrong.
        assertExact("the stack guard", """
                word deep(word n) {
                    if (n == 0) return 0;
                    return deep(n - 1) + 1;
                }
                word main() { return deep(3); }
                """);
    }

    @Test
    @DisplayName("globals, arrays and string literals")
    void dataDirectives() {
        assertExact("data", """
                word counts[4];
                word total = 7;
                void show(word at, byte* text) {
                    while (*text != 0) {
                        __out(9, *text);
                        text = text + 1;
                        at = at + 1;
                    }
                }
                word main() {
                    show(0, "hello");        // a string literal, emitted as DB
                    word sum = total;
                    for (word i = 0; i < 4; i += 1) {
                        counts[i] = i;
                        sum = sum + counts[i];
                    }
                    return sum;
                }
                """);
    }

    @Test
    @DisplayName("every worked example, which is the real corpus")
    void workedExamples() {
        Path dir = Path.of("examples");
        assumeTrue(Files.isDirectory(dir), "examples is missing");
        try (var files = Files.walk(dir)) {
            files.filter(f -> f.toString().endsWith(".mona")).sorted().forEach(file -> {
                String source;
                try {
                    source = Files.readString(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                assertExact(file.getFileName().toString(), source);
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("binary data in a string does not inflate the estimate")
    void escapesAreCountedAsBytes() {
        // A string literal is how a program carries binary -- a tile set, a lookup
        // table -- and the emitter writes anything unprintable as \xNN. Counting the
        // source characters made 32 bytes of tile art look like 128, which reported
        // snake.mona 528 bytes larger than the assembler produced and would refuse a
        // program that fits.
        String art = "\\x00\\xFF\\x0F\\xF0\\x1E\\x78\\x3C\\x3C"
                + "\\xC3\\xC3\\x81\\x81\\x00\\x00\\xAA\\x55";
        String source = "word main() { byte* tile = \"" + art + "\"; return tile[1]; }";

        Oracle.Result r = Oracle.get().run(Mona.compile(source).assembly(), 10_000);
        assertTrue(r.ok(), r::describe);
        assertEquals(255, r.registers().get("A"), "the second byte should be 0xFF");
        assertEquals(r.imageSize(), Mona.imageBytes(source),
                () -> "the estimate and the assembler disagree for a program whose data "
                        + "is all escapes");
    }
}
