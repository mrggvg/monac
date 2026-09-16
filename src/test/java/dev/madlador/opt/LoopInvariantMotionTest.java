package dev.madlador.opt;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What loop-invariant code motion moves, and what it leaves where it is. */
@DisplayName("loop-invariant code motion")
class LoopInvariantMotionTest {

    /** The instructions of the block {@code label}, as the IR dump prints them. */
    private static String block(String ir, String labelSuffix) {
        StringBuilder out = new StringBuilder();
        boolean inside = false;
        for (String line : ir.lines().toList()) {
            if (line.endsWith(":")) inside = line.endsWith(labelSuffix + ":");
            else if (inside) out.append(line).append('\n');
        }
        return out.toString();
    }

    // The loops call something, so every value lives in memory whatever happens and
    // hoisting costs no register: the case where it always pays.
    private static final String PREFIX = "word sink(word v) { return v; }\n";

    @Test
    @DisplayName("an invariant multiply leaves the loop for its preheader")
    void hoistsInvariantArithmetic() {
        String ir = Mona.ir(PREFIX + """
                word run(word a, word b) {
                    word x = a;
                    word y = b;
                    word s = 0;
                    for (word i = 0; i < 4; i++) s = s + sink(x * y);
                    return s;
                }
                word main() { return run(6, 7); }
                """);
        assertTrue(block(ir, "_pre").contains(" * "), () -> "expected x * y in a preheader:\n" + ir);
    }

    @Test
    @DisplayName("a signed comparison's bias of an unchanging side is computed once")
    void hoistsTheBias() {
        String ir = Mona.ir(PREFIX + """
                word run(sword limit) {
                    sword top = limit;
                    word n = 0;
                    for (sword i = 0; i < top; i++) n = n + sink(1);
                    return n;
                }
                word main() { return run(5); }
                """);
        assertTrue(block(ir, "_pre").contains("^ 32768"), () -> "expected top's bias hoisted:\n" + ir);
    }

    @Test
    @DisplayName("a division stays: hoisted out of its guard it could divide by zero")
    void leavesDivisionAlone() {
        String ir = Mona.ir(PREFIX + """
                word run(word a, word b) {
                    word x = a;
                    word y = b;
                    word s = 0;
                    for (word i = 0; i < 4; i++) {
                        if (y != 0) s = s + sink(x / y);
                    }
                    return s;
                }
                word main() { return run(6, 0); }
                """);
        assertFalse(block(ir, "_pre").contains(" / "), () -> "a division was hoisted:\n" + ir);
    }

    @Test
    @DisplayName("a value assigned in the loop is not invariant, whatever its operands")
    void leavesReassignedValuesAlone() {
        String ir = Mona.ir(PREFIX + """
                word run(word a) {
                    word x = a;
                    word s = 0;
                    for (word i = 0; i < 4; i++) {
                        s = s + sink(x + 1);
                        x = x + 2;
                    }
                    return s;
                }
                word main() { return run(1); }
                """);
        assertFalse(block(ir, "_pre").contains(" + 1"), () -> "x + 1 changes every iteration:\n" + ir);
    }
}
