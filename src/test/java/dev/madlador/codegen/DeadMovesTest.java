package dev.madlador.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a call is allowed to be assumed about.
 *
 * <p>Deleting a write that something needed is the worst kind of bug to chase, so the
 * pass used to treat every register as live across a call. That is true of a
 * register-argument convention and not of this one: argument 0 travels in {@code B},
 * the result comes back in {@code A}, and {@code C} is never an input. Nothing survives
 * a call at all — the allocator forbids every register to a value live across one — so a
 * write to {@code A}, {@code B} or {@code C} before a call is dead unless the callee
 * reads it.
 *
 * <p>These are the cases that make the difference visible, and the ones that would break
 * if the convention were characterised even slightly wrong.
 */
@DisplayName("dead moves around a call")
class DeadMovesTest {

    private static Asm.Insn insn(String mnemonic, Asm.Operand... operands) {
        return new Asm.Insn(mnemonic, List.of(operands), null);
    }

    private static Asm.Operand reg(Asm.Reg register) {
        return new Asm.Register(register);
    }

    private static String optimize(List<Asm.Line> lines) {
        return Asm.render(DeadMoves.optimize(lines));
    }

    @Test
    @DisplayName("a load into A before a call is dead, because the call returns in A")
    void loadIntoADiscardedByTheCall() {
        // This is the shape the change actually removes from the corpus: the argument
        // is set up in B, and a stale load into A sits in front of a call that
        // overwrites A with its result.
        String out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.B), new Asm.Indirect(Asm.Reg.SP, 5)),
                insn("MOV", reg(Asm.Reg.A), new Asm.Indirect(Asm.Reg.B, 2)),
                insn("MOV", reg(Asm.Reg.B), new Asm.Indirect(Asm.Reg.B, 2)),
                insn("CALL", new Asm.LabelRef("m_height")),
                insn("MOV", new Asm.Indirect(Asm.Reg.SP, 3), reg(Asm.Reg.A)),
                insn("RET")));

        assertFalse(out.contains("MOV   A, [B+2]"),
                () -> "the call overwrites A before anything reads it:\n" + out);
        assertTrue(out.contains("MOV   B, [B+2]"),
                () -> "but the argument setup must stay:\n" + out);
    }

    @Test
    @DisplayName("the argument in B is still live into the call")
    void theArgumentRegisterSurvives() {
        String out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.B), new Asm.Imm(7)),
                insn("CALL", new Asm.LabelRef("m_f")),
                insn("RET")));

        assertTrue(out.contains("MOV   B, 7"),
                () -> "B carries argument 0, so it is read by the callee:\n" + out);
    }

    @Test
    @DisplayName("an indirect call reads the register holding its target")
    void indirectCallReadsItsBase() {
        // The interrupt trampoline is CALL [B]. If the base were treated as dead the
        // dispatch would jump through whatever happened to be in B.
        String out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.B), new Asm.Absolute("g_isr")),
                insn("CALL", new Asm.Indirect(Asm.Reg.B, 0)),
                insn("RET")));

        assertTrue(out.contains("MOV   B, [g_isr]"),
                () -> "the call's own target register cannot be dead:\n" + out);
    }

    @Test
    @DisplayName("a value the caller wants after a call is in a frame slot, not a register")
    void writesToCBeforeACallAreDead() {
        // C is never an input and never survives, so this write cannot be read by
        // anyone. If this ever stops being true the convention has changed and this
        // test is the place that says so.
        String out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.C), new Asm.Imm(9)),
                insn("CALL", new Asm.LabelRef("m_f")),
                insn("MOV", new Asm.Absolute("g_x"), reg(Asm.Reg.A)),
                insn("RET")));

        assertFalse(out.contains("MOV   C, 9"),
                () -> "C is neither an argument nor preserved:\n" + out);
    }
}
