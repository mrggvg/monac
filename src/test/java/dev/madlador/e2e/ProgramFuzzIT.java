package dev.madlador.e2e;

import dev.madlador.fuzz.Interpreter;
import dev.madlador.fuzz.Prog;
import dev.madlador.fuzz.ProgramGenerator;
import dev.madlador.fuzz.Shrinker;
import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Differential testing over whole programs, rather than over expressions.
 *
 * <p>{@code ExpressionFuzzIT} is the highest bug-per-line test here and it has a
 * specific blind spot: everything it generates is arithmetic over constants. It never
 * emits a variable, a branch, a call, a pointer or an array — and both of the
 * wrong-code bugs this project has shipped lived in exactly those. This closes that
 * gap.
 *
 * <p>Each program is checked three ways, because the bugs found so far needed different
 * oracles. A reference interpreter catches anything wrong at every optimization level,
 * which an {@code -O0}/{@code -O1} differential cannot; the differential catches
 * anything the optimizer breaks, which the reference agrees with by construction; and
 * the stack pointer catches frame accounting, which neither would notice.
 */
@DisplayName("whole-program fuzzing")
class ProgramFuzzIT {

    /**
     * Per profile, per run. {@code -Dfuzz.programs=N} runs more, and
     * {@code -Dfuzz.seedOffset=K} moves every seed by K, so a long campaign needs no
     * edit: {@code mvn -Pe2e verify -Dit.test=ProgramFuzzIT -Dfuzz.programs=2000}.
     */
    private static final int PROGRAMS_PER_PROFILE = Integer.getInteger("fuzz.programs", 60);
    private static final long SEED_OFFSET = Long.getLong("fuzz.seedOffset", 0L);

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    @Test
    @DisplayName("random programs agree with a reference interpreter")
    void generalPrograms() {
        fuzz(ProgramGenerator.Profile.GENERAL, 20260910L);
    }

    @Test
    @DisplayName("pointer-heavy programs, where the known bugs lived")
    void pointerHeavyPrograms() {
        fuzz(ProgramGenerator.Profile.POINTER_HEAVY, 20260911L);
    }

    @Test
    @DisplayName("structs and every width of integer, where the rest of them lived")
    void structsAndNarrowTypes() {
        fuzz(ProgramGenerator.Profile.STRUCTS_AND_BYTES, 20260912L);
    }

    /**
     * The program compiled, or null when it does not fit the machine — too big for
     * 4 KB, or more frame slots than a displacement reaches, which structs make likely
     * at -O0. Those are the machine's limits, not bugs. Anything else the compiler
     * reports is thrown: a random program that fails to compile for another reason is
     * either a generator mistake or a compiler one, and both want seeing.
     */
    private static Mona.Compiled compileIfItFits(String source, int optLevel) {
        try {
            Mona.Compiled compiled = Mona.compile(source, optLevel);
            return compiled.assembly() == null || compiled.assembly().isEmpty() ? null : compiled;
        } catch (AssertionError e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("can be addressed") || message.contains("bytes of RAM")) return null;
            throw e;
        }
    }

    /** Whether compiling a candidate still makes the compiler throw, rather than report. */
    private static boolean crashes(Prog.Program candidate, int optLevel) {
        try {
            new Interpreter(candidate).run();
        } catch (RuntimeException e) {
            return false;   // no longer a program the reference accepts
        }
        try {
            compileIfItFits(Prog.print(candidate), optLevel);
            return false;
        } catch (RuntimeException crash) {
            return true;
        } catch (AssertionError rejected) {
            return false;   // an ordinary compile error: a different thing
        }
    }

    /** What went wrong with this run, or null if nothing did. */
    private static String describeFailure(Prog.Program program, int expected, Oracle.Result r) {
        if (!r.ok()) return "did not run — " + r.error();
        if (!r.halted()) return "never halted";
        int actual = r.registers().get("A");
        if (actual != expected) {
            return "returned " + actual + " where the reference says " + expected;
        }
        if (r.registers().get("SP") != 0x0FFF) {
            return "left SP at " + r.registers().get("SP") + " rather than 0x0FFF";
        }
        return null;
    }

    /**
     * Whether a candidate still fails, which is what the shrinker steers by.
     *
     * <p>A candidate that no longer compiles, or that the reference cannot evaluate, is
     * reported as <em>not</em> failing — otherwise the shrinker would happily reduce a
     * miscompile to a program that is merely broken, and the report would be about the
     * wrong thing.
     */
    private static boolean fails(Prog.Program candidate, int optLevel) {
        int expected;
        try {
            expected = new Interpreter(candidate).run();
        } catch (RuntimeException e) {
            return false;
        }
        Mona.Compiled compiled;
        try {
            compiled = Mona.compile(Prog.print(candidate), optLevel);
        } catch (RuntimeException | AssertionError e) {
            return false;   // a candidate that no longer compiles is not the bug
        }
        if (compiled.assembly() == null || compiled.assembly().isEmpty()) return false;
        Oracle.Result r = Oracle.get().run(compiled.assembly(), 2_000_000);
        return describeFailure(candidate, expected, r) != null;
    }

    private void fuzz(ProgramGenerator.Profile profile, long seed) {
        int checked = 0;
        for (int i = 0; i < PROGRAMS_PER_PROFILE; i++) {
            // One seed per program, so a failure is reproducible from the number in
            // the message alone.
            long programSeed = seed + SEED_OFFSET + i;
            Prog.Program program = new ProgramGenerator(
                    new Random(programSeed), profile).generate();
            String source = Prog.print(program);

            int expected;
            try {
                expected = new Interpreter(program).run();
            } catch (Interpreter.Trap trap) {
                continue;   // the generator built something it should not have; skip
            }

            // Both levels: the reference catches what is wrong at either, and -O0 has
            // no optimizer to hide a lowering bug behind.
            boolean ran = false;
            for (int optLevel : new int[]{0, 1}) {
                Mona.Compiled compiled;
                try {
                    compiled = compileIfItFits(source, optLevel);
                } catch (RuntimeException crash) {
                    // The compiler threw rather than answering: a bug as surely as a
                    // wrong result, and reported the same way — seed, level, and the
                    // smallest program that still makes it throw.
                    int level = optLevel;
                    Prog.Program small = new Shrinker(p -> crashes(p, level)).shrink(program);
                    throw new AssertionError("seed " + programSeed + " at -O" + optLevel
                            + ": the compiler threw " + crash
                            + "\n--- shrunk to ---\n" + Prog.print(small)
                            + "\n--- the original ---\n" + source, crash);
                }
                if (compiled == null) continue;
                Oracle.Result r = Oracle.get().run(compiled.assembly(), 2_000_000);
                String failure = describeFailure(program, expected, r);
                if (failure != null) {
                    // A forty-line random program is not a bug report. Cut it down to
                    // whatever still fails, and show that instead.
                    int level = optLevel;
                    Prog.Program small = new Shrinker(p -> fails(p, level)).shrink(program);
                    throw new AssertionError("seed " + programSeed + " at -O" + optLevel + ": " + failure
                            + "\n--- shrunk from " + Prog.print(program).lines().count()
                            + " lines to " + Prog.print(small).lines().count() + " ---\n"
                            + Prog.print(small)
                            + "\n--- the original ---\n" + source);
                }
                ran = true;
            }
            if (ran) checked++;
        }
        assertTrue(checked > PROGRAMS_PER_PROFILE / 2,
                "only " + checked + " of " + PROGRAMS_PER_PROFILE
                        + " programs were actually checked; the generator is producing "
                        + "too much it cannot use");
    }
}
