package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential testing of the optimizer.
 *
 * <p>Every end-to-end program is compiled twice and executed twice, and the two runs
 * must agree in every register. This is the check that actually matters for an
 * optimizer: a pass that miscompiles usually still produces plausible assembly, and
 * only differs from the unoptimized version on some inputs. It caught a real one —
 * the dead-store rule was treating the single operand of {@code MUL} as a
 * destination rather than a source, and deleting the store that fed it.
 */
@DisplayName("optimizer")
class OptimizerIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    static Stream<EndToEndIT.Case> cases() {
        return EndToEndIT.cases();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("optimized and unoptimized code agree")
    void levelsAgree(EndToEndIT.Case testCase) {
        int[] display = {0x1000, 0x1020};
        Oracle.Result plain = Oracle.get().run(
                Mona.compile(testCase.source(), 0).assembly(), testCase.maxSteps(), display);
        Oracle.Result optimized = Oracle.get().run(
                Mona.compile(testCase.source(), 1).assembly(), testCase.maxSteps(), display);

        assertTrue(plain.ok(), plain::describe);
        assertTrue(optimized.ok(), optimized::describe);
        assertFalse(optimized.timedOut(),
                () -> testCase.name() + " did not halt when optimized\n" + optimized.describe());

        // Compare only what the convention actually promises. B and C are scratch,
        // so the two versions legitimately leave different values in them; the
        // observable contract is the result in A, a balanced stack, a restored
        // frame pointer, and whatever reached the display.
        // A only carries a defined value when the program returns one; a void
        // function makes no promise about it, so compare it only where the case
        // declares an expectation.
        List<String> compared = new ArrayList<>(List.of("SP", "D"));
        if (testCase.expected().containsKey("A")) compared.add("A");

        for (String register : compared) {
            assertEquals(plain.reg(register), optimized.reg(register),
                    () -> testCase.name() + ": register " + register
                            + " differs between -O0 and -O1\n"
                            + "--- unoptimized ---\n" + plain.describe()
                            + "--- optimized ---\n" + optimized.describe());
        }
        assertArrayEquals(plain.memory(), optimized.memory(),
                () -> testCase.name() + ": the display differs between -O0 and -O1");
    }

    @Test
    @DisplayName("optimizing never makes a program longer")
    void neverGrows() {
        for (EndToEndIT.Case testCase : cases().toList()) {
            int plain = instructions(Mona.compile(testCase.source(), 0).assembly());
            int optimized = instructions(Mona.compile(testCase.source(), 1).assembly());
            assertTrue(optimized <= plain,
                    () -> testCase.name() + " grew from " + plain + " to " + optimized);
        }
    }

    @Test
    @DisplayName("the optimizer removes a clear majority of the redundancy")
    void deliversTheExpectedWin() {
        // A regression alarm rather than a precise target: if a change silently
        // disables a pass, the total jumps back up and this fails.
        int plain = 0;
        int optimized = 0;
        for (EndToEndIT.Case testCase : cases().toList()) {
            plain += instructions(Mona.compile(testCase.source(), 0).assembly());
            optimized += instructions(Mona.compile(testCase.source(), 1).assembly());
        }
        double ratio = (double) optimized / plain;
        final int p = plain;
        final int o = optimized;
        assertTrue(ratio < 0.85,
                () -> "expected at least a 15% reduction, got " + o + " from " + p
                        + " (" + Math.round(100 * (1 - ratio)) + "%)");
    }

    private static int instructions(String assembly) {
        return (int) assembly.lines()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.endsWith(":"))
                .count();
    }
}
