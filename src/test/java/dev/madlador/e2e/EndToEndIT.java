package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compiles each program in {@code src/test/resources/e2e} and runs it on the real
 * simulator, checking the expectations written in its header.
 *
 * <pre>
 * // expect: A=30
 * // maxSteps: 5000
 * word main() { ... }
 * </pre>
 *
 * <p>Adding a language feature means adding one {@code .mona} file, which is what
 * keeps the milestone plan cheap to execute.
 *
 * <p>Two invariants are asserted for every program, whatever its header says: after
 * {@code HLT} the stack pointer is back at {@code 0x0FFF} and no fault occurred.
 * Between them those catch essentially every calling-convention bug.
 */
@DisplayName("end to end")
class EndToEndIT {

    private static final int STACK_TOP = 0x0FFF;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** The memory-mapped text display: two lines of sixteen characters. */
    private static final int DISPLAY_START = 0x1000;
    private static final int DISPLAY_END = 0x1020;

    public record Case(String name, String source, Map<String, Integer> expected,
                       String display, int maxSteps) {
        @Override
        public String toString() {
            return name;
        }
    }

    public static Stream<Case> cases() {
        Path dir;
        try {
            dir = Path.of(EndToEndIT.class.getClassLoader().getResource("e2e").toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("src/test/resources/e2e is missing", e);
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Case> found = new ArrayList<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".mona")).sorted().toList()) {
                found.add(parse(file));
            }
            return found.stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Case parse(Path file) {
        String source;
        try {
            source = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Integer> expected = new LinkedHashMap<>();
        String display = null;
        int maxSteps = 100_000;

        for (String line : source.lines().toList()) {
            String text = line.strip();
            if (!text.startsWith("//")) continue;
            text = text.substring(2).strip();

            if (text.startsWith("expect:")) {
                for (String part : text.substring("expect:".length()).split(",")) {
                    String[] halves = part.strip().split("=", 2);
                    if (halves.length == 2) {
                        expected.put(halves[0].strip(), parseValue(halves[1].strip()));
                    }
                }
            } else if (text.startsWith("display:")) {
                display = text.substring("display:".length()).strip();
            } else if (text.startsWith("maxSteps:")) {
                maxSteps = Integer.parseInt(text.substring("maxSteps:".length()).strip());
            }
        }
        String name = file.getFileName().toString().replace(".mona", "");
        return new Case(name, source, expected, display, maxSteps);
    }

    /** The display as text, with trailing blanks and NULs trimmed. */
    private static String readDisplay(Oracle.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int b : result.memory()) {
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : ' ');
        }
        return sb.toString().stripTrailing();
    }

    private static int parseValue(String text) {
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Integer.parseInt(text.substring(2), 16);
        }
        // Written as a signed number for readability; the machine is 16-bit unsigned.
        return Integer.parseInt(text) & 0xFFFF;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void runsCorrectly(Case testCase) {
        String assembly = Mona.compile(testCase.source()).assembly();
        int[] dump = testCase.display() == null ? null : new int[]{DISPLAY_START, DISPLAY_END};
        Oracle.Result result = Oracle.get().run(assembly, testCase.maxSteps(), dump);

        assertFalse(result.timedOut(),
                () -> testCase.name() + " did not halt within " + testCase.maxSteps()
                        + " steps\n" + result.describe());
        assertEquals(true, result.ok(), result::describe);
        assertFalse(result.fault(), () -> testCase.name() + " faulted\n" + result.describe());

        testCase.expected().forEach((register, value) ->
                assertEquals(value, result.reg(register),
                        () -> testCase.name() + ": register " + register + "\n" + result.describe()));

        if (testCase.display() != null) {
            assertEquals(testCase.display(), readDisplay(result),
                    () -> testCase.name() + ": display contents\n" + result.describe());
        }

        // Universal invariants: a balanced stack and a restored frame pointer.
        assertEquals(STACK_TOP, result.sp(),
                () -> testCase.name() + ": the stack is unbalanced\n" + result.describe());
        assertEquals(0, result.d(),
                () -> testCase.name() + ": the frame pointer was not restored\n" + result.describe());
    }
}
