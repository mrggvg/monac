package dev.madlador.codegen;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules that are easier to state on the assembly than on the IR.
 *
 * <p>Correctness of what these produce is covered by running every program at both
 * optimization levels in {@code OptimizerIT}; these are about whether the rule fires
 * where it should and holds still where it should not.
 */
class PeepholeTest {

    private static List<Asm.Line> optimize(List<Asm.Line> lines) {
        return Peephole.optimize(lines, Set.of());
    }

    private static Asm.Insn branch(String mnemonic, String target) {
        return new Asm.Insn(mnemonic, List.of(new Asm.LabelRef(target)), null);
    }

    @Test
    @DisplayName("a branch over a jump becomes the opposite branch")
    void invertsBranchOverJump() {
        //      JZ  .taken            becomes      JNZ .skipped
        //      JMP .skipped
        //  .taken:                                .taken:
        List<Asm.Line> out = optimize(List.of(
                branch("JZ", ".taken"),
                branch("JMP", ".skipped"),
                new Asm.Label(".taken"),
                new Asm.Insn("RET"),
                new Asm.Label(".skipped"),
                new Asm.Insn("RET")));

        String rendered = Asm.render(out);
        assertTrue(rendered.contains("JNZ   .skipped"), rendered);
        assertFalse(rendered.contains("JZ    .taken"), rendered);
        assertFalse(rendered.contains("JMP"), () -> "the jump is folded away:\n" + rendered);
    }

    @Test
    @DisplayName("every condition the machine has is inverted correctly")
    void invertsEveryCondition() {
        // No sign flag, so these three pairs are all there are. Inverting anything
        // else would have to invent a complement the hardware does not have.
        String[][] pairs = {{"JZ", "JNZ"}, {"JNZ", "JZ"}, {"JC", "JNC"},
                            {"JNC", "JC"}, {"JA", "JNA"}, {"JNA", "JA"}};
        for (String[] pair : pairs) {
            List<Asm.Line> out = optimize(List.of(
                    branch(pair[0], ".taken"),
                    branch("JMP", ".skipped"),
                    new Asm.Label(".taken"),
                    new Asm.Insn("RET")));
            assertEquals(pair[1], ((Asm.Insn) out.get(0)).mnemonic(),
                    pair[0] + " should invert to " + pair[1]);
        }
    }

    @Test
    @DisplayName("a branch whose target is not the next label is left alone")
    void leavesUnrelatedBranchesAlone() {
        // Falling through does not land on .taken here, so folding the jump in would
        // send the not-taken path somewhere it was never going.
        List<Asm.Line> out = optimize(List.of(
                branch("JZ", ".taken"),
                branch("JMP", ".skipped"),
                new Asm.Label(".elsewhere"),
                new Asm.Insn("RET"),
                new Asm.Label(".taken"),
                new Asm.Insn("RET")));

        assertEquals("JZ", ((Asm.Insn) out.get(0)).mnemonic());
        assertEquals("JMP", ((Asm.Insn) out.get(1)).mnemonic());
    }

    @Test
    @DisplayName("an indirect jump is never folded into a branch")
    void leavesIndirectJumpsAlone() {
        // JMP [A] is how a switch reaches its case, and it names no label to invert
        // the branch towards.
        List<Asm.Line> out = optimize(List.of(
                branch("JZ", ".taken"),
                new Asm.Insn("JMP", List.of(new Asm.Indirect(Asm.Reg.A, 0)), null),
                new Asm.Label(".taken"),
                new Asm.Insn("RET")));

        assertEquals("JZ", ((Asm.Insn) out.get(0)).mnemonic());
        assertEquals("JMP", ((Asm.Insn) out.get(1)).mnemonic());
    }

    @Test
    @DisplayName("a real conditional loses its jump")
    void firesOnCompiledCode() {
        // The shape lowering produces for every if, loop test and short-circuit:
        // the true edge as a branch, the false edge as a jump.
        String assembly = Mona.compile("""
                word main() {
                    word n = 0;
                    while (n < 10) n = n + 1;
                    return n;
                }
                """).assembly();
        // Counted inside main only: the entry stub has a JMP of its own.
        String body = assembly.substring(assembly.indexOf("m_main:"));
        long jumps = body.lines().filter(l -> l.strip().startsWith("JMP")).count();
        assertEquals(1, jumps,
                () -> "the loop should need only its back edge:\n" + assembly);
    }

    private static Asm.Insn insn(String mnemonic, Asm.Operand... operands) {
        return new Asm.Insn(mnemonic, List.of(operands), null);
    }

    private static Asm.Operand reg(Asm.Reg register) {
        return new Asm.Register(register);
    }

    @Test
    @DisplayName("writing AL invalidates a tracked mirror of A")
    void aByteWriteToALowHalfInvalidates() {
        // removeRedundantLoads tracks one fact -- "this location currently mirrors A"
        // -- and drops a reload of that location. A write to AL or AH changes A
        // without naming it, so it has to clear the fact. Comparing the half against
        // A does not see it, and neither does matching the string "AL".
        //
        // The reload below MUST survive: by the time it is reached, A holds the byte
        // that MOVB put there, not the word that was stored.
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Absolute("g_x"), reg(Asm.Reg.A)),   // [g_x] mirrors A
                insn("MOVB", reg(Asm.Reg.AL), new Asm.Indirect(Asm.Reg.B, 0)),
                insn("MOVB", reg(Asm.Reg.AH), new Asm.Imm(0)),
                insn("MOV", reg(Asm.Reg.A), new Asm.Absolute("g_x"))));

        String rendered = Asm.render(out);
        assertTrue(rendered.contains("MOV   A, [g_x]"),
                () -> "the reload was deleted, so A holds the byte and not the word:\n"
                        + rendered);
    }

    @Test
    @DisplayName("writing a base register's half invalidates a name relative to it")
    void aByteWriteToABaseHalfInvalidates() {
        // The same hole one level out: [B+2] is tracked, and BL is written, which
        // moves where [B+2] points.
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Indirect(Asm.Reg.B, 2), reg(Asm.Reg.A)),
                insn("MOVB", reg(Asm.Reg.BL), new Asm.Imm(0)),
                insn("MOV", reg(Asm.Reg.A), new Asm.Indirect(Asm.Reg.B, 2))));

        String rendered = Asm.render(out);
        assertTrue(rendered.contains("MOV   A, [B+2]"),
                () -> "B moved under the tracked name, so the reload must stay:\n" + rendered);
    }

    @Test
    @DisplayName("but a genuinely redundant reload is still removed")
    void stillRemovesARealRedundantLoad() {
        // The fix must not simply disable the rule.
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Absolute("g_x"), reg(Asm.Reg.A)),
                insn("MOV", reg(Asm.Reg.B), new Asm.Imm(7)),
                insn("MOV", reg(Asm.Reg.A), new Asm.Absolute("g_x"))));

        String rendered = Asm.render(out);
        assertFalse(rendered.contains("MOV   A, [g_x]"),
                () -> "nothing here changes A, so the reload is redundant:\n" + rendered);
    }

    private static long count(List<Asm.Line> lines, String rendered) {
        return lines.stream().filter(l -> l instanceof Asm.Insn)
                .filter(l -> Asm.render(List.of(l)).strip().replaceAll("\\s+", " ").equals(rendered))
                .count();
    }

    @Test
    @DisplayName("a word stored from B and read straight back into B is not reloaded")
    void dropsReloadIntoB() {
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Absolute("g_x"), reg(Asm.Reg.B)),
                insn("MOV", reg(Asm.Reg.B), new Asm.Absolute("g_x")),
                new Asm.Insn("RET")));
        assertEquals(0, count(out, "MOV B, [g_x]"), () -> Asm.render(out));
    }

    @Test
    @DisplayName("a copy between registers is remembered both ways")
    void dropsTheCopyBack() {
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.B), reg(Asm.Reg.A)),
                insn("MOV", reg(Asm.Reg.A), reg(Asm.Reg.B)),
                new Asm.Insn("RET")));
        assertEquals(0, count(out, "MOV A, B"), () -> Asm.render(out));
    }

    @Test
    @DisplayName("moving the base a location is addressed through forgets it")
    void forgetsLocationsThroughAChangedBase() {
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Indirect(Asm.Reg.B, 0), reg(Asm.Reg.C)),   // [B] holds C
                insn("MOV", reg(Asm.Reg.B), new Asm.Absolute("g_p")),          // B moves
                insn("MOV", reg(Asm.Reg.C), new Asm.Indirect(Asm.Reg.B, 0)),   // a different [B]
                new Asm.Insn("RET")));
        assertEquals(1, count(out, "MOV C, [B]"), () -> Asm.render(out));
    }

    @Test
    @DisplayName("a store through a pointer forgets every remembered location")
    void aStoreThroughAPointerForgetsMemory() {
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", new Asm.Absolute("g_x"), reg(Asm.Reg.B)),
                insn("MOV", new Asm.Indirect(Asm.Reg.C, 0), reg(Asm.Reg.A)),   // C may be &g_x
                insn("MOV", reg(Asm.Reg.B), new Asm.Absolute("g_x")),
                new Asm.Insn("RET")));
        assertEquals(1, count(out, "MOV B, [g_x]"), () -> Asm.render(out));
    }

    @Test
    @DisplayName("a load through the register it loads is never taken for a repeat")
    void aSelfAddressedLoadIsNotRemembered() {
        // **p through a spilled pointer: the second load reads a different word.
        List<Asm.Line> out = optimize(List.of(
                insn("MOV", reg(Asm.Reg.A), new Asm.Indirect(Asm.Reg.A, 0)),
                insn("MOV", reg(Asm.Reg.A), new Asm.Indirect(Asm.Reg.A, 0)),
                new Asm.Insn("RET")));
        assertEquals(2, count(out, "MOV A, [A]"), () -> Asm.render(out));
    }
}
