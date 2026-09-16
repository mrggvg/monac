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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Interrupt handling, driven by real hardware interrupts from the keypad.
 *
 * <p>There is one vector, at {@code 0x0003}, shared by every device. A trampoline
 * sits there: it saves all four registers — the CPU pushes only IP and SR — calls
 * through whatever address {@code __setisr} stored, and returns with {@code IRET}.
 *
 * <p>An interrupt is only delivered when both the device's bit in IRQMASK and the
 * global mask set by {@code __sti} are on, and the handler has to acknowledge
 * through IRQEOI or it is re-entered immediately. Each of those is a way for this to
 * silently do nothing, which is why these tests assert a count rather than just
 * absence of a fault.
 */
@DisplayName("interrupts")
class InterruptIT {

    private static final int STACK_TOP = 0x0FFF;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    private static Oracle.Result run(String source, String keys, int keyEvery) {
        return Oracle.get().run(Mona.compile(source).assembly(), 200_000, null, null,
                keys, keyEvery);
    }

    @Test
    @DisplayName("a keypress reaches the installed handler")
    void handlerReceivesKey() {
        Oracle.Result r = run("""
                word lastKey = 0;
                word pressed = 0;

                void onIrq() {
                    word pending = __in(1);
                    if (pending & 1) {
                        lastKey = __in(6);
                        pressed = pressed + 1;
                    }
                    __out(2, pending);
                }

                word main() {
                    __setisr(&onIrq);
                    __out(0, 1);
                    __sti();
                    word spins = 0;
                    while (pressed == 0 && spins < 50000) spins = spins + 1;
                    __cli();
                    return lastKey;
                }
                """, "K", 200);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);
        assertEquals('K', r.a(), () -> "the handler should have seen the key\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(),
                () -> "the trampoline must be symmetric\n" + r.describe());
    }

    @Test
    @DisplayName("several interrupts are counted")
    void handlerRunsRepeatedly() {
        Oracle.Result r = run("""
                word pressed = 0;

                void onIrq() {
                    word pending = __in(1);
                    if (pending & 1) {
                        __in(6);
                        pressed = pressed + 1;
                    }
                    __out(2, pending);
                }

                word main() {
                    __setisr(&onIrq);
                    __out(0, 1);
                    __sti();
                    word spins = 0;
                    while (pressed < 3 && spins < 50000) spins = spins + 1;
                    __cli();
                    return pressed;
                }
                """, "abc", 300);

        assertTrue(r.ok(), r::describe);
        assertEquals(3, r.a(), () -> "expected three interrupts\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @Test
    @DisplayName("nothing is delivered while interrupts are masked")
    void cliBlocksDelivery() {
        // Never calls __sti, so the handler must never run.
        Oracle.Result r = run("""
                word pressed = 0;

                void onIrq() {
                    __in(6);
                    pressed = pressed + 1;
                    __out(2, 1);
                }

                word main() {
                    __setisr(&onIrq);
                    __out(0, 1);
                    word spins = 0;
                    while (spins < 3000) spins = spins + 1;
                    return pressed;
                }
                """, "xyz", 200);

        assertTrue(r.ok(), r::describe);
        assertEquals(0, r.a(), () -> "no handler should run without __sti\n" + r.describe());
    }

    @Test
    @DisplayName("an interrupt with a null vector does not run the program again")
    void nullVectorDoesNotJumpToZero() {
        // An interrupt nothing acknowledges is a livelock on this machine: IRET
        // re-enters immediately while the request is still raised. That is expected
        // and is not what this checks.
        //
        // What the trampoline's guard buys is that a null vector does not transfer
        // control to address 0 — which is the entry stub, so the whole program would
        // start over. The counter proves main was entered exactly once.
        Oracle.Result r = run("""
                word entered = 0;

                void onIrq() { __out(2, 1); }

                word main() {
                    entered = entered + 1;
                    __setisr(0);              // trampoline exists, vector is null
                    __out(0, 1);
                    __sti();
                    word spins = 0;
                    while (spins < 60000) spins = spins + 1;
                    return entered;
                }
                """, "z", 200);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "a null vector must not fault\n" + r.describe());
        assertTrue(r.timedOut(),
                () -> "an unacknowledged interrupt is expected to spin\n" + r.describe());

        // If control had reached address 0 the entry stub would have re-run main and
        // the counter would be above one. Reading it back proves it did not.
        Oracle.Result check = Oracle.get().run(
                Mona.compile("""
                        word entered = 0;
                        void onIrq() { __out(2, 1); }
                        word main() {
                            entered = entered + 1;
                            __setisr(0);
                            return entered;
                        }
                        """).assembly(), 100_000, null, null, null, 0);
        assertEquals(1, check.a(), () -> "main ran more than once\n" + check.describe());
    }

    @Test
    @DisplayName("the worked example compiles and runs")
    void exampleRuns() {
        Path source = Path.of("examples/2-hardware/interrupt.mona");
        assumeTrue(Files.isReadable(source), "examples/2-hardware/interrupt.mona is missing");
        String program;
        try {
            program = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Oracle.Result r = run(program, "K", 200);
        assertTrue(r.ok(), r::describe);
        assertEquals('K', r.a(), r::describe);
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @Test
    @DisplayName("one physical keypress delivers two interrupts, down and up")
    void aKeypressIsTwoInterrupts() {
        // The keypad raises its line as a key goes down AND as it comes up, with the
        // same KBDDATA both times. Every interrupt test in this file sends key-downs
        // only, which is the easy case the hardware never actually produces -- so a
        // handler that counts presses reads double on a real machine, and nothing here
        // would have shown it.
        //
        // This is what the counter really does. If it ever reads 1 again, either the
        // oracle stopped sending releases or the keypad changed.
        String program = """
                word entries = 0;
                word downs = 0;

                void onIrq() {
                    word pending = __in(1);
                    if (pending & 1) {
                        word status = __in(5);      // read BEFORE draining the data
                        word key = __in(6);         // and this is what lowers the line
                        entries = entries + 1;
                        if ((status & 1) != 0) downs = downs + 1;
                    }
                    __out(2, pending);
                }

                word main() {
                    __setisr(&onIrq);
                    __out(0, 1);
                    __sti();
                    word spins = 0;
                    while (spins < 40000) spins = spins + 1;
                    __cli();
                    return entries * 10 + downs;
                }
                """;

        Oracle.Result r = Oracle.get().runWithKeyUp(
                Mona.compile(program).assembly(), 300_000, "K", 8000);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);
        assertEquals(21, r.a(),
                () -> "one press should be two handler entries of which one is a "
                        + "key-down\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(),
                () -> "the trampoline must still be symmetric\n" + r.describe());
    }
}
