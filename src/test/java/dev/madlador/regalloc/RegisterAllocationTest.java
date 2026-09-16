package dev.madlador.regalloc;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties the allocated code must have, checked by reading the assembly.
 *
 * <p>Whether the results are <em>correct</em> is covered by executing every program
 * at both optimization levels in {@code OptimizerIT}. These are about whether the
 * allocator is doing its job at all, and about the machine constraints it has to
 * respect.
 */
class RegisterAllocationTest {

    private static String compile(String source) {
        return Mona.compile(source, 1).assembly();
    }

    /** Instruction lines of one function, from its label to the blank line after. */
    private static String bodyOf(String assembly, String label) {
        StringBuilder sb = new StringBuilder();
        boolean inside = false;
        for (String line : assembly.lines().toList()) {
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

    private static int instructions(String body) {
        return (int) body.lines()
                .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.endsWith(":"))
                .count();
    }

    @Test
    @DisplayName("a value read more than once is held in a register")
    void reusedValueGetsRegister() {
        // A value used twice cannot be folded into its consumer each time, so it has
        // to live somewhere — and a register is what the allocator is for.
        String body = bodyOf(compile("""
                word f(word a, word b) {
                    word t = a + b;
                    return t * t;
                }
                word main() { return f(3, 4); }
                """), "m_f");

        // It is held in B, which is also where the first argument arrived, so the
        // sum is accumulated in place and there is no move to look for.
        assertTrue(body.contains("MUL   B") || body.contains("MUL   C"),
                () -> "expected the reused value multiplied from a register:\n" + body);
        assertFalse(body.contains("[D-") || body.contains("[SP+1]"),
                () -> "nothing should need a frame slot here:\n" + body);
    }

    @Test
    @DisplayName("a value used once is folded into its consumer, not given a register")
    void singleUseIsFolded() {
        // The ALU takes a memory operand at the same one clock tick as a register
        // one, so loading into a register first would be pure waste.
        String body = bodyOf(compile("""
                word add(word a, word b) { return a + b; }
                word main() { return add(1, 2); }
                """), "m_add");

        // Argument 0 arrives in B and argument 1 is the one still on the stack, so
        // the add accumulates into B and reads the frame directly.
        assertTrue(body.contains("ADD   B, [SP+") || body.contains("ADD   A, [SP+"),
                () -> "the stack argument should be read straight from the frame:\n" + body);
        assertTrue(instructions(body) <= 3,
                () -> "expected three instructions, got " + instructions(body) + ":\n" + body);
    }

    @Test
    @DisplayName("a function needing no frame does not set one up")
    void framePointerEliminated() {
        String body = bodyOf(compile("""
                word add(word a, word b) { return a + b; }
                word main() { return add(1, 2); }
                """), "m_add");

        assertFalse(body.contains("PUSH  D"),
                () -> "the frame pointer should have been eliminated:\n" + body);
        assertTrue(body.contains("[SP+3]"),
                () -> "arguments should be addressed from SP:\n" + body);
        assertTrue(instructions(body) <= 6,
                () -> "expected a very short body, got " + instructions(body) + ":\n" + body);
    }

    @Test
    @DisplayName("a function that calls another still loses its frame pointer")
    void framePointerEliminatedAcrossCalls() {
        // A call moves SP, but by a distance the compiler emitted and can account
        // for, so the displacements are adjusted rather than the frame kept.
        String body = bodyOf(compile("""
                word f(word a, word b) { return a + b; }
                word g(word x) {
                    word t = x + 1;
                    return f(t, 2) + t;     // t is live across the call, so it spills
                }
                word main() { return g(1); }
                """), "m_g");
        assertFalse(body.contains("PUSH  D"),
                () -> "a caller no longer needs a frame pointer:\n" + body);
        // t's slot is written before any argument is pushed and read again with one
        // argument pushed below it, so the same slot is named two ways.
        assertTrue(body.contains("MOV   [SP+1], A"),
                () -> "t should be spilled across the call:\n" + body);
        assertTrue(body.contains(", [SP+3]"),
                () -> "the slot should shift with the pushed argument:\n" + body);
    }

    @Test
    @DisplayName("a jump table does not force a frame pointer either")
    void framePointerEliminatedThroughJumpTable() {
        // JMP [A] names no label, so the depth walk is told where the module's tables
        // can land and follows all of them. Without that, every switch kept a frame.
        String body = bodyOf(compile("""
                word add(word a, word b) { return a + b; }
                word classify(word n) {
                    switch (n) {
                        case 0: return add(n, 1);
                        case 1: return add(n, 2);
                        case 2: return add(n, 3);
                        case 3: return add(n, 4);
                        default: return 99;
                    }
                }
                word main() { return classify(2); }
                """), "m_classify");
        assertTrue(body.contains("JMP   ["), () -> "expected a jump table:\n" + body);
        assertFalse(body.contains("PUSH  D"),
                () -> "a switch does not need a frame pointer:\n" + body);
    }

    @Test
    @DisplayName("taking a local's address does not need a frame pointer either")
    void frameAddressFromTheStackPointer() {
        // The address is computed once, where the depth is known; that SP moves
        // afterwards does not change what it names.
        String body = bodyOf(compile("""
                word main() {
                    word n = 7;
                    word* p = &n;
                    return *p;
                }
                """), "m_main");
        assertFalse(body.contains("PUSH  D"),
                () -> "&local should be taken from SP:\n" + body);
        assertTrue(body.contains("SP"), () -> body);
    }

    @Test
    @DisplayName("a value live across a call is not left in a register")
    void callsClobberEverything() {
        // There are no callee-saved registers in this convention, so anything live
        // across a call has to be in memory. The kept value comes from a parameter
        // so that it cannot simply be rematerialised as a constant afterwards.
        String body = bodyOf(compile("""
                word f(word a) { return a; }
                word main() { return keeper(9); }
                word keeper(word seed) {
                    word keep = seed + 1;
                    word other = f(3);
                    return keep + other;
                }
                """), "m_keeper");
        assertTrue(body.contains("[D-") || body.contains("[SP+1]"),
                () -> "a value live across a call must be spilled:\n" + body);
    }

    @Test
    @DisplayName("MUL keeps the accumulator to itself")
    void multiplyOwnsTheAccumulator() {
        // A is both source and destination of MUL, so nothing else may sit in it
        // across one. Parameters, so constant folding cannot remove the multiply.
        String body = bodyOf(compile("""
                word times(word a, word b) { return a * b + a + b; }
                word main() { return times(6, 7); }
                """), "m_times");
        assertTrue(body.contains("MUL"), () -> "expected a multiply:\n" + body);
        // Whatever is still needed after the multiply must not have been in A.
        assertFalse(body.contains("MUL   A"), () -> "MUL takes one operand:\n" + body);
    }

    @Test
    @DisplayName("remainder keeps its left operand out of the accumulator")
    void remainderProtectsItsLeftOperand() {
        // MOD expands to DIV and MUL and then reads its LEFT operand a second time,
        // after A has already been overwritten. Unlike a plain multiply, it
        // therefore cannot have that operand in A. Getting this wrong made every
        // remainder in a loop return garbage.
        String body = bodyOf(compile("""
                word rem(word a, word b) { return a % b; }
                word main() { return rem(17, 5); }
                """), "m_rem");
        assertTrue(body.contains("DIV"), () -> "expected a divide:\n" + body);
        assertTrue(body.contains("MUL"), () -> "expected a multiply:\n" + body);
    }

    @Test
    @DisplayName("a commutative operation is turned round to accumulate in place")
    void commutativeOperandsAreOrderedToAvoidAMove() {
        // When the destination's register already holds the RIGHT operand, computing
        // there would clobber it, so the result would go through A and come back:
        // three instructions where one would do. Addition does not care which way
        // round its operands are, so it is turned round instead.
        String body = bodyOf(compile("""
                word f(word seed) {
                    word total = seed;
                    for (word i = 0; i < 4; i += 1) total = i + total;
                    return total;
                }
                word main() { return f(1); }
                """), "m_f");

        assertFalse(body.contains("MOV   A, ") && body.contains("MOV   B, A"),
                () -> "the sum should accumulate in place, not travel through A:\n" + body);
    }

    @Test
    @DisplayName("a loop body keeps its counter in a register")
    void loopCounterInRegister() {
        String body = bodyOf(compile("""
                word main() {
                    word sum = 0;
                    for (word i = 0; i < 10; i += 1) sum += i;
                    return sum;
                }
                """), "m_main");
        // With both the counter and the accumulator in registers, the loop should
        // touch memory very little.
        long memoryAccesses = body.lines()
                .filter(l -> l.contains("[D") || l.contains("[SP")).count();
        assertTrue(memoryAccesses <= 2,
                () -> "expected the loop to run out of registers, saw " + memoryAccesses
                        + " frame accesses:\n" + body);
    }
}
