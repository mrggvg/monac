package dev.madlador.oracle;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Executable documentation of the target machine.
 *
 * <p>Every fact the code generator relies on is pinned here by running it on the real
 * simulator. If a future change to the bundle (or a wrong assumption of ours) breaks
 * one of these, it shows up as a named failing test rather than as mysteriously wrong
 * generated code.
 */
@DisplayName("target machine contract")
class MachineContractIT {

    private static Oracle sim;

    @BeforeAll
    static void startOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
        sim = Oracle.get();
    }

    @Test
    @DisplayName("MUL is single-operand with an implicit A accumulator")
    void mulIsSingleOperand() {
        Oracle.Result r = sim.run("""
                MOV SP, 0x0FFF
                MOV A, 7
                MOV B, 6
                MUL B
                HLT
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(42, r.a(), r::describe);

        // The two-operand form the old code generator emitted does not exist.
        assertFalse(sim.run("MUL A, B").assembled(), "MUL A, B must not assemble");
    }

    @Test
    @DisplayName("there is no MOD instruction")
    void noModInstruction() {
        Oracle.Result r = sim.run("MOD A, B");
        assertFalse(r.assembled(), r::describe);
        assertEquals("assemble", r.phase());
    }

    @Test
    @DisplayName("indirect operands must not contain spaces")
    void bracketsRejectSpaces() {
        assertTrue(sim.run("MOV A, [D-2]\nHLT").assembled(), "[D-2] must assemble");
        assertFalse(sim.run("MOV A, [D - 2]\nHLT").assembled(), "[D - 2] must NOT assemble");
    }

    @Test
    @DisplayName("negative immediates cannot be written; use two's complement")
    void negativeImmediatesRejected() {
        assertFalse(sim.run("MOV A, -5\nHLT").assembled(), "MOV A, -5 must NOT assemble");

        Oracle.Result r = sim.run("MOV A, 65531\nHLT");
        assertTrue(r.ok(), r::describe);
        assertEquals(65531, r.a(), "-5 is emitted as 65531");
    }

    @Test
    @DisplayName("labels: uppercased, register names and leading underscores rejected")
    void labelHazards() {
        assertFalse(sim.run("foo: HLT\nFoo: HLT").assembled(), "labels are case-insensitive, so these collide");
        assertFalse(sim.run("A: HLT").assembled(), "a label may not be a register name");
        assertFalse(sim.run("SP: HLT").assembled(), "a label may not be a register name");
        assertFalse(sim.run("_start: HLT").assembled(), "a label may not start with an underscore");

        assertTrue(sim.run(".start: HLT").assembled(), "dotted labels are fine");
        assertTrue(sim.run("m_main: HLT").assembled(), "mangled user symbols are fine");
    }

    @Test
    @DisplayName("the stack pointer addresses the free byte below the top word")
    void stackPointerSkew() {
        // PUSH writes the word at SP-1 and leaves SP two lower, so the value just
        // pushed is readable at [SP+1]. Every frame offset is odd because of this.
        Oracle.Result r = sim.run("""
                MOV SP, 0x0FFF
                MOV A, 1234
                PUSH A
                MOV B, [SP+1]
                HLT
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(1234, r.b(), r::describe);
        assertEquals(0x0FFD, r.sp(), "PUSH lowers SP by two");
    }

    @Test
    @DisplayName("ALU instructions accept a memory source operand")
    void aluFoldsMemorySources() {
        // This is what makes spilling cheap and memory-operand folding worthwhile.
        assertTrue(sim.run("ADD A, [D+7]\nHLT").assembled(), "ADD from an indirect operand");
        assertTrue(sim.run("SUB A, [0x0100]\nHLT").assembled(), "SUB from a direct address");
        assertTrue(sim.run("CMP A, [D+5]\nHLT").assembled(), "CMP against memory");
        assertTrue(sim.run("DIV [D-3]\nHLT").assembled(), "DIV by a memory operand");
        assertTrue(sim.run("MUL 10\nHLT").assembled(), "MUL by an immediate");
        assertTrue(sim.run("MOV [D-1], 5\nHLT").assembled(), "store an immediate straight to memory");
    }

    @Test
    @DisplayName("there is no read-modify-write on memory, and no register-indirect CALL")
    void unsupportedOperandForms() {
        assertFalse(sim.run("PUSH [D-1]").assembled(), "PUSH takes a register or immediate only");
        assertFalse(sim.run("ADD [D-1], A").assembled(), "memory cannot be an ALU destination");
        assertFalse(sim.run("INC [D-1]").assembled(), "INC takes a register only");
        assertFalse(sim.run("CALL B").assembled(), "CALL does not take a bare register");
        assertTrue(sim.run("CALL [B]\nHLT").assembled(), "CALL through a memory slot is how indirect calls work");
    }

    @Test
    @DisplayName("the entry stub places IRET exactly on the 0x0003 interrupt vector")
    void entryStubLayout() {
        Oracle.Result r = sim.run("""
                    JMP .start
                .irq:
                    IRET
                .start:
                    MOV SP, 0x0FFF
                    MOV A, 99
                    HLT
                """, 100, new int[]{0, 4});
        assertTrue(r.ok(), r::describe);
        assertEquals(99, r.a(), r::describe);
        assertEquals(0x2E, r.memory()[0], "JMP <word> opcode at address 0");
        assertEquals(0x84, r.memory()[3], "IRET must land on the interrupt vector at 0x0003");
    }

    @Test
    @DisplayName("the calling convention restores SP and D exactly")
    void callingConvention() {
        // Args pushed in reverse so the first argument sits nearest the frame base:
        //   [D+1] saved D, [D+3] return address, [D+5] first arg, [D+7] second arg,
        //   [D-1], [D-3], ... locals.
        Oracle.Result r = sim.run("""
                        MOV SP, 0x0FFF
                        MOV A, 3
                        PUSH A              ; second argument
                        MOV A, 20
                        PUSH A              ; first argument
                        CALL m_sub
                        ADD SP, 4           ; caller pops the arguments
                        HLT
                m_sub:
                        PUSH D
                        MOV D, SP
                        SUB SP, 2           ; one word of locals
                        MOV A, [D+5]
                        MOV B, [D+7]
                        SUB A, B
                        MOV [D-1], A
                        MOV A, 0
                        MOV A, [D-1]
                        MOV SP, D
                        POP D
                        RET
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(17, r.a(), r::describe);
        assertEquals(0x0FFF, r.sp(), "the stack must be balanced on return");
        assertEquals(0, r.d(), "the frame pointer must be restored");
        assertFalse(r.fault(), r::describe);
    }

    @Test
    @DisplayName("a runaway program times out instead of hanging the build")
    void infiniteLoopTimesOut() {
        Oracle.Result r = sim.run("MOV SP, 0x0FFF\n.loop: JMP .loop", 50);
        assertTrue(r.ok(), r::describe);
        assertTrue(r.timedOut(), "should report a timeout");
        assertFalse(r.halted(), "should not claim to have halted");
        assertEquals(50, r.steps());
    }

    @Test
    @DisplayName("division by zero faults")
    void divideByZeroFaults() {
        Oracle.Result r = sim.run("""
                MOV SP, 0x0FFF
                MOV A, 10
                MOV B, 0
                DIV B
                HLT
                """);
        assertFalse(r.ok(), "a divide by zero is reported as a run-phase failure");
        assertEquals("run", r.phase());
    }
}
