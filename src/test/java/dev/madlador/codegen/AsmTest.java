package dev.madlador.codegen;

import dev.madlador.sema.Mangler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules the target assembler enforces, tested where they are implemented.
 *
 * <p>These are cheap, pure checks on the exact thing that made the previous
 * generator's output unassemblable. {@code MachineContractIT} proves the rules are
 * real by running the assembler; this proves we obey them.
 */
class AsmTest {

    @Test
    @DisplayName("indirect operands are written without spaces")
    void indirectHasNoSpaces() {
        // "[D - 2]" is a syntax error: the assembler's line regex is
        // \[(\w+((\+|-)\d+)?)\], which allows no whitespace inside the brackets.
        assertEquals("[D-2]", Asm.render(new Asm.Indirect(Asm.Reg.D, -2)));
        assertEquals("[D+5]", Asm.render(new Asm.Indirect(Asm.Reg.D, 5)));
        assertEquals("[SP+1]", Asm.render(new Asm.Indirect(Asm.Reg.SP, 1)));
        assertEquals("[B]", Asm.render(new Asm.Indirect(Asm.Reg.B, 0)));

        String line = Asm.render(new Asm.Insn("MOV",
                new Asm.Register(Asm.Reg.A), new Asm.Indirect(Asm.Reg.D, -1)));
        assertTrue(line.contains("[D-1]"), line);
        assertFalse(line.contains("[D -"), line);
        assertFalse(line.contains("- 1]"), line);
    }

    @Test
    @DisplayName("immediates are always emitted unsigned")
    void immediatesAreUnsigned() {
        // "MOV A, -5" is rejected outright, so -5 has to be written as 65531.
        assertEquals("65531", Asm.render(new Asm.Imm(-5)));
        assertEquals("65535", Asm.render(new Asm.Imm(-1)));
        assertEquals("0", Asm.render(new Asm.Imm(0)));
        assertEquals("65535", Asm.render(new Asm.Imm(0xFFFF)));

        assertFalse(Asm.render(new Asm.Imm(-1)).contains("-"));
    }

    @Test
    @DisplayName("an out-of-range displacement is rejected at construction")
    void displacementRange() {
        // Better to fail here than to let the assembler report
        // "offset must be a value between -128...+127" about generated code.
        assertThrows(IllegalArgumentException.class, () -> new Asm.Indirect(Asm.Reg.D, 128));
        assertThrows(IllegalArgumentException.class, () -> new Asm.Indirect(Asm.Reg.D, -129));
        new Asm.Indirect(Asm.Reg.D, 127);
        new Asm.Indirect(Asm.Reg.D, -128);
    }

    @Test
    @DisplayName("frame offsets are odd, following the stack pointer skew")
    void frameOffsetsAreOdd() {
        // SP addresses the free byte below the top word, so every frame offset is odd.
        assertEquals(-1, FrameLayout.slotOffset(0));
        assertEquals(-3, FrameLayout.slotOffset(1));
        assertEquals(-5, FrameLayout.slotOffset(2));

        // Argument 0 travels in a register, so argument 1 is the first on the stack
        // and sits where argument 0 used to.
        assertEquals(5, FrameLayout.argOffset(1));
        assertEquals(7, FrameLayout.argOffset(2));
        assertThrows(IllegalArgumentException.class, () -> FrameLayout.argOffset(0));

        assertEquals("[D-1]", Asm.render(FrameLayout.local(0)));
        // Argument 0 travels in a register, so argument 1 is the first on the stack.
        assertEquals("[D+5]", Asm.render(FrameLayout.argument(1)));
    }

    @Test
    @DisplayName("the frame slot limit matches the displacement range")
    void frameLimits() {
        assertTrue(FrameLayout.slotFits(FrameLayout.MAX_SLOTS - 1));
        assertFalse(FrameLayout.slotFits(FrameLayout.MAX_SLOTS));
        assertTrue(FrameLayout.argFits(FrameLayout.MAX_ARGS - 1));
        assertFalse(FrameLayout.argFits(FrameLayout.MAX_ARGS));
        // Argument 0 always fits: it never reaches the stack to need a displacement.
        assertTrue(FrameLayout.argFits(0));
    }

    @Test
    @DisplayName("labels are rendered with a colon and instructions are indented")
    void lineFormatting() {
        assertEquals("m_main:", Asm.render(new Asm.Label("m_main")));
        assertEquals("; hello", Asm.render(new Asm.Comment("hello")));
        assertTrue(Asm.render(new Asm.Insn("RET")).startsWith("        "));
    }

    @Test
    @DisplayName("mangling avoids every label the assembler rejects")
    void manglingAvoidsAssemblerRules() {
        Mangler mangler = new Mangler();

        // A label may not spell a register name, nor start with an underscore.
        for (String reserved : List.of("a", "b", "c", "d", "sp", "ah", "al", "sr")) {
            String label = mangler.forFunction(reserved);
            assertNotEquals(reserved.toUpperCase(), label.toUpperCase(),
                    "a function called '" + reserved + "' must not become a bare register name");
            assertTrue(label.startsWith("m_"), label);
        }
        assertTrue(mangler.forFunction("_start").startsWith("m_"),
                "a leading underscore is a syntax error, so the prefix must come first");
    }

    @Test
    @DisplayName("names differing only in case get distinct labels")
    void manglingDisambiguatesCase() {
        // The assembler uppercases labels, so foo and Foo would otherwise collide.
        Mangler mangler = new Mangler();
        String first = mangler.forFunction("foo");
        String second = mangler.forFunction("Foo");
        assertNotEquals(first.toUpperCase(), second.toUpperCase(),
                "labels are compared case-insensitively by the assembler");
    }

    @Test
    @DisplayName("internal labels are unique across the whole module")
    void internalLabelsAreModuleWide() {
        // The label namespace is global, so per-function numbering would collide.
        Mangler mangler = new Mangler();
        String a = mangler.internal("loop");
        String b = mangler.internal("loop");
        assertNotEquals(a, b);
        assertTrue(a.startsWith("."), "dotted labels are accepted; underscore-led ones are not");
    }
}
