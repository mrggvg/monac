package dev.madlador.regalloc;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first argument travels in {@code B}.
 *
 * <p>What makes it pay is that nothing special-cases it. The argument arrives as an
 * {@code ArgIn} defining an ordinary virtual register, so the allocator decides
 * whether it can stay in {@code B} — free — or has to be written to a frame slot
 * because the function makes a call of its own. Both outcomes are checked here,
 * because the second one is the cost and it should appear only where it is earned.
 *
 * <p>Correctness is covered by executing every program at both optimization levels
 * in {@code OptimizerIT}; these are about the shape of what comes out.
 */
class CallingConventionTest {

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

    private static String compile(String source) {
        return Mona.compile(source, 1).assembly();
    }

    @Test
    @DisplayName("a one-argument call pushes nothing and reclaims nothing")
    void oneArgumentCallTouchesNoStack() {
        String body = bodyOf(compile("""
                word twice(word n) { return n + n; }
                word main() { return twice(21); }
                """), "m_main");

        assertFalse(body.contains("PUSH"),
                () -> "the only argument goes in a register:\n" + body);
        assertFalse(body.contains("reclaim"),
                () -> "nothing was pushed, so nothing is reclaimed:\n" + body);
    }

    @Test
    @DisplayName("a leaf function reads its first argument straight out of B")
    void leafKeepsTheArgumentInTheRegister() {
        // No call means B survives, so the value never reaches memory: no store on
        // the way in and no load on the way out.
        String body = bodyOf(compile("""
                word add(word a, word b) { return a + b; }
                word main() { return add(1, 2); }
                """), "m_add");

        assertTrue(body.contains("B"), () -> "the argument should be used from B:\n" + body);
        assertFalse(body.contains("SUB   SP"),
                () -> "a leaf function needs no frame at all here:\n" + body);
    }

    @Test
    @DisplayName("a function that calls does spill its register argument, once")
    void callerStoresTheArgument() {
        // B cannot survive a call, so the argument is written to a frame slot — and
        // that store is the whole cost of the convention. It should appear exactly
        // where it is needed and nowhere else.
        String body = bodyOf(compile("""
                word other(word n) { return n; }
                word f(word a) { return other(a) + a; }
                word main() { return f(5); }
                """), "m_f");

        long stores = body.lines().filter(l -> l.matches("MOV\\s+\\[SP\\+\\d+\\], B")).count();
        assertEquals(1, stores,
                () -> "expected one store of the register argument:\n" + body);
    }

    @Test
    @DisplayName("further arguments still go on the stack, one word lower than before")
    void remainingArgumentsAreStillPushed() {
        String body = bodyOf(compile("""
                word pick(word a, word b, word c) { return b + c; }
                word main() { return pick(1, 2, 3); }
                """), "m_main");

        assertTrue(body.contains("PUSH  3") && body.contains("PUSH  2"),
                () -> "arguments 1 and 2 are pushed, deepest first:\n" + body);
        assertFalse(body.contains("PUSH  1"),
                () -> "argument 0 is not pushed:\n" + body);
        assertTrue(body.contains("ADD   SP, 4"),
                () -> "two words were pushed, so four bytes come back:\n" + body);
    }

    @Test
    @DisplayName("argument 0 is placed after every push, so a later argument may clobber B")
    void registerArgumentIsPlacedLast() {
        // The hazard the ordering exists for: computing argument 1 here is a call,
        // which destroys B. Argument 0 therefore has to be spilled and reloaded after
        // the push — and the reload names its slot through a displacement that the
        // push has already shifted, which is frame-pointer elimination doing its job
        // underneath this one.
        String body = bodyOf(compile("""
                word inner(word n) { return n + 1; }
                word pick(word a, word b) { return a * 10 + b; }
                word outer(word x) { return pick(inner(x), inner(x + 5)); }
                word main() { return outer(1); }
                """), "m_outer");

        var lines = body.lines().toList();
        int lastPush = -1;
        int callPick = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("PUSH")) lastPush = i;
            if (lines.get(i).startsWith("CALL  m_pick")) callPick = i;
        }
        assertTrue(lastPush >= 0 && callPick > lastPush,
                () -> "expected a push before the call:\n" + body);

        boolean placedAfterPush = lines.subList(lastPush + 1, callPick).stream()
                .anyMatch(l -> l.startsWith("MOV   B,"));
        assertTrue(placedAfterPush,
                () -> "argument 0 must be put in B after the pushes, not before:\n" + body);
    }

    @Test
    @DisplayName("taking the argument's address is what forces it into memory")
    void addressTakenArgumentIsStored() {
        String body = bodyOf(compile("""
                void bump(word* p) { *p = *p + 1; }
                word f(word a) { bump(&a); return a; }
                word main() { return f(1); }
                """), "m_f");

        assertTrue(body.contains(", B"),
                () -> "the register argument has to be written somewhere:\n" + body);
        assertFalse(body.contains("PUSH  D"),
                () -> "an address in the frame is taken from SP, with no frame pointer:\n" + body);
    }
}
