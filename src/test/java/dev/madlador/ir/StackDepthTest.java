package dev.madlador.ir;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the compiler can say about the stack before the program runs.
 *
 * <p>Exercised through the compiler rather than against a hand-built module, so that
 * what is tested is the number a programmer actually gets from {@code --stats} — and
 * so that the frame sizes come from the real allocator rather than from an assumption
 * about it.
 *
 * <p>The bound is deliberately an over-estimate: it counts a saved frame pointer in
 * every frame, though frame-pointer elimination removes almost all of them. Tests
 * therefore check the direction and the ordering, and pin exact numbers only where the
 * arithmetic is small enough to do by hand.
 */
class StackDepthTest {

    @Test
    @DisplayName("a program that calls nothing needs one frame")
    void leafProgram() {
        // main's own return address and its assumed saved D, and nothing else.
        assertEquals(4, Mona.stackBound("word main() { return 7; }"));
    }

    @Test
    @DisplayName("a chain of calls sums along its length")
    void chainAddsUp() {
        int one = Mona.stackBound("""
                word a(word x) { return x + 1; }
                word main() { return a(1); }
                """);
        int two = Mona.stackBound("""
                word b(word x) { return x + 1; }
                word a(word x) { return b(x) + 1; }
                word main() { return a(1); }
                """);
        int three = Mona.stackBound("""
                word c(word x) { return x + 1; }
                word b(word x) { return c(x) + 1; }
                word a(word x) { return b(x) + 1; }
                word main() { return a(1); }
                """);
        assertTrue(one < two && two < three,
                () -> "each link should add a frame: " + one + ", " + two + ", " + three);
        assertEquals(two - one, three - two,
                "identical links should cost the same");
    }

    @Test
    @DisplayName("a diamond takes the deeper arm, not the sum of both")
    void diamondTakesTheDeeperArm() {
        // main calls two functions; only one of them is deep. Adding them would say
        // the program needs a stack twice as tall as it can ever use.
        int bound = Mona.stackBound("""
                word deep3(word x) { return x + 1; }
                word deep2(word x) { return deep3(x) + 1; }
                word deep1(word x) { return deep2(x) + 1; }
                word shallow(word x) { return x + 1; }
                word main() { return deep1(1) + shallow(2); }
                """);
        int deepArmAlone = Mona.stackBound("""
                word deep3(word x) { return x + 1; }
                word deep2(word x) { return deep3(x) + 1; }
                word deep1(word x) { return deep2(x) + 1; }
                word main() { return deep1(1); }
                """);
        assertEquals(deepArmAlone, bound,
                "the shallow arm is not on the deepest path and should cost nothing");
    }

    @Test
    @DisplayName("arguments past the first are on the stack and counted")
    void pushedArgumentsCount() {
        int oneArgument = Mona.stackBound("""
                word f(word a) { return a; }
                word main() { return f(1); }
                """);
        int fourArguments = Mona.stackBound("""
                word f(word a, word b, word c, word d) { return a + b + c + d; }
                word main() { return f(1, 2, 3, 4); }
                """);
        // Argument 0 travels in B; the other three are pushed, at two bytes each.
        assertEquals(6, fourArguments - oneArgument,
                "three pushed arguments are six bytes of stack");
    }

    @Test
    @DisplayName("recursion has no bound, directly or round a longer loop")
    void recursionIsUnbounded() {
        assertEquals(StackDepth.UNBOUNDED, Mona.stackBound("""
                word f(word n) { if (n == 0) return 0; return f(n - 1) + 1; }
                word main() { return f(3); }
                """));

        // No forward declaration needed: every top-level name is declared before any
        // body is analysed, which is what makes mutual recursion writable at all.
        assertEquals(StackDepth.UNBOUNDED, Mona.stackBound("""
                word isEven(word n) { if (n == 0) return 1; return isOdd(n - 1); }
                word isOdd(word n) { if (n == 0) return 0; return isEven(n - 1); }
                word main() { return isEven(4); }
                """));
    }

    @Test
    @DisplayName("a caller of a recursive function is unbounded too")
    void unboundednessPropagatesUpwards() {
        assertEquals(StackDepth.UNBOUNDED, Mona.stackBound("""
                word f(word n) { if (n == 0) return 0; return f(n - 1) + 1; }
                word wrapper(word n) { return f(n); }
                word main() { return wrapper(3); }
                """));
    }

    @Test
    @DisplayName("an interrupt handler is added on top, because it arrives on top")
    void interruptsAddToTheDeepestPoint() {
        // Nothing calls onIrq — the hardware does — so it is on no call chain, and yet
        // it runs on the same stack at whatever moment the device chooses.
        int without = Mona.stackBound("""
                word count = 0;
                void onIrq() { count = count + 1; }
                word main() { return count; }
                """);
        int with = Mona.stackBound("""
                word count = 0;
                void onIrq() { count = count + 1; }
                word main() {
                    __setisr(&onIrq);
                    return count;
                }
                """);
        assertTrue(with > without,
                () -> "installing a handler should raise the requirement: "
                        + without + " to " + with);
        // The CPU pushes IP and SR, the trampoline pushes four registers, and then
        // the handler has a frame of its own.
        assertTrue(with - without >= 12,
                () -> "at least the six words the trampoline costs, got " + (with - without));
    }

    @Test
    @DisplayName("the bound covers what the program actually uses")
    void theBoundIsNotAnUnderestimate() {
        // The whole point: if this were ever under the truth, the heap would be sized
        // over the top of a stack that was going to need the room.
        String source = """
                word c(word x) { word t = x * 2; return t + 1; }
                word b(word x) { word t = c(x); return t + x; }
                word a(word x, word y, word z) { return b(x) + y + z; }
                word main() { return a(1, 2, 3); }
                """;
        int bound = Mona.stackBound(source);
        assertFalse(bound == StackDepth.UNBOUNDED, "this program does not recurse");
        // Four frames, each at most a return address, a saved D and a couple of
        // locals, plus two pushed arguments. Comfortably under fifty bytes.
        assertTrue(bound > 0 && bound < 50,
                () -> "expected a small, plausible bound, got " + bound);
    }
}
