package dev.madlador.e2e;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.driver.Compiler;
import dev.madlador.driver.Options;
import dev.madlador.ir.HeapStrategy;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real data structures, run until the heap says no, over and over.
 *
 * <p>The corpus is a good check that the compiler handles each feature. It is a poor
 * check that the allocator survives being used, because a corpus program allocates a
 * handful of nodes and stops. The programs under {@code src/test/resources/stress/}
 * do the opposite: each fills the heap to exhaustion, empties it, and does that again,
 * for millions of instructions.
 *
 * <p>They are also the heaviest pointer code in the repository, which is the second
 * reason for them. A red-black rotation rewrites six links through five struct
 * members; a pairing heap's merge rewrites its way through a whole sibling list. Both
 * of the wrong-code bugs found in this compiler were stores through pointers into
 * struct members, so this is the shape most likely to find the next one.
 *
 * <p>Every program checks itself and returns zero, so there is nothing to assert
 * beyond the answer — but each one verifies structure rather than just finishing. The
 * tree walks itself in order, the queue checks what comes out is sorted, and the
 * red-black tree checks all four of its invariants including the black height, which
 * is what catches a rebalance that is wrong but still produces a searchable tree.
 *
 * <p>They run under each allocator the program is eligible for, because the point is
 * to stress the allocators and there are three of them.
 */
@DisplayName("heap stress")
class HeapStressIT {

    private static final Path DIRECTORY = Path.of("src/test/resources/stress");

    /**
     * These run for millions of instructions each, so the budget is generous and the
     * assertion that matters is that they halt well inside it.
     */
    private static final int BUDGET = 60_000_000;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
        assumeTrue(Files.isDirectory(DIRECTORY), DIRECTORY + " is missing");
    }

    /** One program under one allocator at one optimization level. */
    record Run(String program, HeapStrategy.Kind tier, int optLevel) {
        @Override
        public String toString() {
            return program + " / " + tier.name().toLowerCase() + " / -O" + optLevel;
        }
    }

    static Stream<Arguments> runs() {
        List<Arguments> all = new ArrayList<>();
        try (var files = Files.list(DIRECTORY)) {
            files.filter(f -> f.toString().endsWith(".mona")).sorted().forEach(file -> {
                String name = file.getFileName().toString().replace(".mona", "");
                for (HeapStrategy.Kind tier : HeapStrategy.Kind.values()) {
                    for (int opt : new int[]{0, 1}) {
                        all.add(Arguments.of(new Run(name, tier, opt)));
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return all.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("runs")
    @DisplayName("fills the heap, empties it, and checks itself throughout")
    void survives(Run run) {
        String source = read(DIRECTORY.resolve(run.program() + ".mona"));

        SourceFile file = new SourceFile(run.program() + ".mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result compiled;
        try {
            compiled = new Compiler(file, reporter)
                    .compile(Options.Stage.ASM, true, run.optLevel(), 0, run.tier());
        } catch (RuntimeException e) {
            throw new AssertionError(run + " failed to compile\n" + reporter.render(), e);
        }
        if (reporter.hasErrors()) {
            // The red-black tree does not fit in 4 KB unoptimized: 5,993 bytes at -O0
            // against 3,132 at -O1. Being told so is the fit check working — the same
            // limit cube.mona runs into — so that combination is skipped rather than
            // failed, and skipped loudly. Any other diagnostic is a real failure.
            // Specifically the 4 KB message. "does not fit in 16 bits" is a
            // different diagnostic about a literal, and matching both would turn a
            // broken program into a quiet skip.
            if (reporter.render().contains("bytes of RAM")) {
                abort(run + " does not fit in 4 KB unoptimized, which the fit check "
                        + "correctly says; -O1 is where this program lives");
            }
            throw new AssertionError(run + " reported errors\n" + reporter.render());
        }

        Oracle.Result r = Oracle.get().run(compiled.assembly(), BUDGET);

        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> run + " faulted\n" + r.describe());
        assertTrue(r.halted(), () -> run + " did not finish inside " + BUDGET
                + " instructions\n" + r.describe());
        assertEquals(0x0FFF, r.sp(),
                () -> run + " left the stack unbalanced\n" + r.describe());
        assertEquals(0, r.a(),
                () -> run + " reported failure code " + r.a()
                        + " — see the program for what that number means\n" + r.describe());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
