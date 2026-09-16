package dev.madlador.oracle;

import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.driver.Compiler;
import dev.madlador.driver.Options;

/**
 * Compiles Mona source in-process, for tests.
 */
public final class Mona {

    private Mona() {
    }

    /** A successful compilation, plus any warnings it produced. */
    public record Compiled(String assembly, String diagnostics) {
    }

    /** Compiles to assembly, failing the caller if there are errors. */
    public static Compiled compile(String source) {
        return compile(source, 1);
    }

    /** Compiles at a specific optimization level. */
    public static Compiled compile(String source, int optLevel) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        try {
            Compiler.Result result =
                    new Compiler(file, reporter).compile(Options.Stage.ASM, true, optLevel);
            if (reporter.hasErrors()) {
                throw new AssertionError("compilation failed:\n" + reporter.render());
            }
            return new Compiled(result.assembly(), reporter.render());
        } catch (CompileException e) {
            throw new AssertionError("compilation failed:\n" + reporter.render(), e);
        }
    }

    /** How many bytes the compiled image occupies, of the machine's 4096. */
    public static int imageBytes(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result =
                new Compiler(file, reporter).compile(Options.Stage.ASM, true, 1);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return result.imageBytes();
    }

    /** The most stack the program can need, or {@code StackDepth.UNBOUNDED}. */
    public static int stackBound(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result =
                new Compiler(file, reporter).compile(Options.Stage.ASM, true, 1);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return result.stackBound();
    }

    /** Compiles without the entry stub, as {@code --no-entry} does. */
    public static Compiled compileBare(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result =
                new Compiler(file, reporter).compile(Options.Stage.ASM, false, 1);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return new Compiled(result.assembly(), reporter.render());
    }

    /** Compiles and runs, returning the machine state after HLT. */
    public static Oracle.Result run(String source) {
        return Oracle.get().run(compile(source).assembly());
    }

    /** All diagnostics from compiling {@code source}, whether or not it succeeded. */
    public static String diagnose(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        try {
            new Compiler(file, reporter).compile(Options.Stage.ASM, true);
        } catch (CompileException ignored) {
            // the reporter holds the detail
        }
        return reporter.render();
    }

    /** The IR dump, for tests that should not depend on instruction selection. */
    public static String ir(String source) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result = new Compiler(file, reporter).compile(Options.Stage.IR, true);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return Compiler.describe(result, Options.Stage.IR);
    }
}
