package dev.madlador.opt;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strength reduction, and the operand ordering it depends on.
 *
 * <p>Worth restating why this matters here, because the usual reason does not apply:
 * {@code MUL 2} and {@code SHL x, 1} both cost one clock tick, and {@code MUL WORD}
 * is the <em>smaller</em> encoding. The win is that {@code MUL} and {@code DIV} read
 * and write {@code A} implicitly, so every one of them forces the value through the
 * accumulator, while a shift writes wherever the value already lives.
 */
class StrengthReduceTest {

    /** The body of one function, for reading what was emitted. */
    private static String bodyOf(String source, String label) {
        StringBuilder sb = new StringBuilder();
        boolean inside = false;
        for (String line : Mona.compile(source).assembly().lines().toList()) {
            if (line.startsWith(label + ":")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (line.isBlank()) break;
                sb.append(line.strip()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Compiles {@code return <expression>;} over a parameter, so nothing folds away. */
    private static String forExpression(String expression) {
        return bodyOf("word f(word x) { return " + expression + "; }\n"
                + "word main() { return f(3); }\n", "m_f");
    }

    @Test
    @DisplayName("multiplying by a power of two becomes a shift")
    void multiplyBecomesShift() {
        String body = forExpression("x * 8");
        assertTrue(body.contains("SHL"), () -> body);
        assertFalse(body.contains("MUL"), () -> "a shift avoids pinning A:\n" + body);
    }

    @Test
    @DisplayName("the constant may be on either side")
    void constantOnTheLeftIsAlsoReduced() {
        // Commutative operations are canonicalised so the constant ends up on the
        // right, where every rule looks for it. Without that, `8 * x` kept its
        // multiply while `x * 8` became a shift — the same expression compiling
        // differently depending on which way round it happened to be typed.
        String body = forExpression("8 * x");
        assertTrue(body.contains("SHL"), () -> "8 * x should reduce as well:\n" + body);
        assertFalse(body.contains("MUL"), () -> body);
    }

    @Test
    @DisplayName("dividing by a power of two becomes a shift")
    void divideBecomesShift() {
        String body = forExpression("x / 4");
        assertTrue(body.contains("SHR"), () -> body);
        assertFalse(body.contains("DIV"), () -> body);
    }

    @Test
    @DisplayName("remainder by a power of two becomes a mask")
    void remainderBecomesMask() {
        // The largest of these wins: a general remainder has no instruction on this
        // machine and expands to a divide, a multiply and a subtract.
        String body = forExpression("x % 8");
        assertTrue(body.contains("AND"), () -> body);
        assertFalse(body.contains("DIV"), () -> body);
        assertFalse(body.contains("MUL"), () -> body);
    }

    @Test
    @DisplayName("a non-power of two still multiplies")
    void otherConstantsAreLeftAlone() {
        assertTrue(forExpression("x * 3").contains("MUL"), "3 is not a shift");
    }

    @Test
    @DisplayName("non-commutative operations are never reordered")
    void orderIsPreservedWhereItMatters() {
        // 8 / x is not x / 8, and 8 - x is not x - 8. Canonicalisation only touches
        // commutative operations; getting that wrong would be silent and wrong for
        // almost every input.
        assertTrue(forExpression("8 / x").contains("DIV"), "8 / x cannot become a shift");
        assertTrue(forExpression("8 - x").contains("SUB"), "8 - x cannot be reordered");
    }

    @Test
    @DisplayName("an immediate on the left folds into the instruction")
    void commutativeImmediateFolds() {
        // The same canonicalisation lets the selector use the constant as an
        // immediate operand rather than loading it into a register first.
        String body = forExpression("5 + x");
        assertTrue(body.contains("ADD") || body.contains("INC"), () -> body);
        assertFalse(body.contains("MOV   B, 5"), () -> "5 should be an immediate:\n" + body);
    }
}
