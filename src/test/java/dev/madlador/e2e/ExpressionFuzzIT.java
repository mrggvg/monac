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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential testing: generate random expressions, evaluate them in Java, compile
 * and run them on the real machine, and compare.
 *
 * <p>This is the highest bug-per-line test in the project. Operand ordering is the
 * thing it is really guarding: {@code SUB}, {@code DIV}, {@code SHL} and {@code SHR}
 * are not commutative, and swapping their operands produces code that looks
 * plausible and is wrong for most inputs. The previous code generator did exactly
 * that. A hand-written suite finds such bugs only if someone thought to write the
 * asymmetric case; this finds them in seconds.
 *
 * <p>The reference evaluation is deliberately written in terms of unsigned 16-bit
 * arithmetic, because that is what {@code word} means.
 */
@DisplayName("expression fuzzing")
class ExpressionFuzzIT {

    private static final int EXPRESSIONS_PER_BATCH = 40;
    private static final int BATCHES = 5;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    /** A generated expression: its Mona text and its expected 16-bit value. */
    private record Generated(String text, int value) {
    }

    @Test
    @DisplayName("random arithmetic agrees with a reference evaluation")
    void arithmeticMatchesReference() {
        // A fixed seed per batch keeps failures reproducible while still covering
        // a wide space across the run.
        for (int batch = 0; batch < BATCHES; batch++) {
            Random random = new Random(20260905L + batch);
            checkBatch(random, batch);
        }
    }

    private void checkBatch(Random random, int batch) {
        List<Generated> generated = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        // Several independent expressions per program, summed into the result, so
        // one compile-and-run covers many cases.
        body.append("word main() {\n");
        body.append("    word acc = 0;\n");
        for (int i = 0; i < EXPRESSIONS_PER_BATCH; i++) {
            Generated g = generate(random, 3);
            generated.add(g);
            body.append("    acc = acc + (").append(g.text()).append(");\n");
        }
        body.append("    return acc;\n}\n");

        int expected = 0;
        for (Generated g : generated) expected = (expected + g.value()) & 0xFFFF;

        Oracle.Result result = Oracle.get().run(Mona.compile(body.toString()).assembly());
        assertFalse(result.timedOut(), result::describe);
        assertFalse(result.fault(), result::describe);

        int actual = result.a();
        if (actual != expected) {
            // Narrow it down so the failure names one expression, not forty.
            for (Generated g : generated) {
                Oracle.Result single = Oracle.get().run(Mona.compile(
                        "word main() { return " + g.text() + "; }").assembly());
                assertEquals(g.value(), single.a(),
                        () -> "batch " + batch + ": " + g.text() + "\n" + single.describe());
            }
        }
        assertEquals(expected, actual, () -> "batch " + batch + "\n" + result.describe());
    }

    /** Builds a random expression tree and its reference value together. */
    private Generated generate(Random random, int depth) {
        if (depth == 0 || random.nextInt(100) < 25) {
            int value = random.nextInt(1000);
            return new Generated(Integer.toString(value), value);
        }

        Generated left = generate(random, depth - 1);
        Generated right = generate(random, depth - 1);

        String[] operators = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>"};
        String op = operators[random.nextInt(operators.length)];

        // Division and remainder by zero fault on this machine, and a shift count
        // above 15 is not meaningful for a 16-bit value.
        if (("/".equals(op) || "%".equals(op)) && right.value() == 0) {
            op = "+";
        }
        if ("<<".equals(op) || ">>".equals(op)) {
            int count = right.value() & 0xF;
            right = new Generated(Integer.toString(count), count);
        }

        int value = evaluate(op, left.value(), right.value());
        String text = "(" + left.text() + " " + op + " " + right.text() + ")";
        return new Generated(text, value);
    }

    /** The reference semantics: unsigned 16-bit throughout. */
    private static int evaluate(String op, int a, int b) {
        int result = switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> Integer.divideUnsigned(a, b);
            case "%" -> Integer.remainderUnsigned(a, b);
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << b;
            case ">>" -> a >>> b;
            default -> throw new IllegalArgumentException(op);
        };
        return result & 0xFFFF;
    }

    @Test
    @DisplayName("non-commutative operators are not silently swapped")
    void nonCommutativeOperands() {
        // Each of these gives a different, plausible-looking answer if the operands
        // are exchanged, which is exactly the bug the old generator had.
        assertEquals(7, Mona.run("word main() { return 10 - 3; }").a());
        assertEquals(65533, Mona.run("word main() { return 3 - 6; }").a());
        assertEquals(3, Mona.run("word main() { return 17 / 5; }").a());
        assertEquals(0, Mona.run("word main() { return 5 / 17; }").a());
        assertEquals(2, Mona.run("word main() { return 17 % 5; }").a());
        assertEquals(5, Mona.run("word main() { return 5 % 17; }").a());
        assertEquals(64, Mona.run("word main() { return 1 << 6; }").a());
        assertEquals(24, Mona.run("word main() { return 6 << 4 >> 2; }").a());
        assertEquals(2, Mona.run("word main() { return 64 >> 5; }").a());
    }
}
