package dev.madlador;

import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.driver.Compiler;
import dev.madlador.driver.Options;

import java.io.IOException;
import java.nio.file.Files;

public class Main {

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (Options.UsageException e) {
            if (e.getMessage() != null) {
                System.err.println("monac: " + e.getMessage());
                System.err.println();
                System.err.print(Options.USAGE);
                System.exit(2);
            }
            System.out.print(Options.USAGE);
            return;
        }

        try {
            System.exit(run(options));
        } catch (IOException e) {
            System.err.println("monac: " + e.getMessage());
            System.exit(1);
        }
    }

    private static int run(Options options) throws IOException {
        if (!Files.isReadable(options.input)) {
            System.err.println("monac: cannot read " + options.input);
            return 1;
        }

        SourceFile source = new SourceFile(options.input.toString(), Files.readString(options.input));
        DiagnosticReporter reporter = new DiagnosticReporter(source);
        reporter.setWarningsAreErrors(options.warnError);

        Options.Stage stage = options.finalStage();
        Compiler.Result result;
        try {
            result = new Compiler(source, reporter).compile(stage, !options.noEntry,
                    options.optLevel, options.heapSize, options.heapStrategy);
        } catch (CompileException e) {
            System.err.print(reporter.render());
            if (reporter.diagnostics().isEmpty()) {
                System.err.println("monac: " + e.getMessage());
            }
            return 1;
        }

        System.err.print(reporter.render());
        if (reporter.hasErrors()) return 1;

        String rendered = Compiler.describe(result, stage);

        if (stage != Options.Stage.ASM) {
            System.out.print(rendered);
            return 0;
        }

        if (options.outputToStdout || options.dumpAsm) {
            System.out.print(rendered);
        }
        if (!options.outputToStdout && options.output != null) {
            Files.writeString(options.output, rendered);
        }
        if (options.stats) {
            long instructions = rendered.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith(";") && !l.endsWith(":"))
                    .count();
            // Three, because they answer different questions: instructions are
            // cycles, since every one costs a single tick; bytes are whether it fits;
            // and the stack is how much of what is left the program will want back.
            System.err.println("monac: " + instructions + " instructions, "
                    + result.imageBytes() + " bytes of "
                    + dev.madlador.codegen.ModuleEmitter.RAM_LIMIT);
            System.err.println("monac: stack " + describeStack(result.stackBound())
                    + ", " + (dev.madlador.codegen.ModuleEmitter.RAM_LIMIT
                            - result.imageBytes()) + " bytes free");
        }
        return 0;
    }

    /** The stack requirement, or why there is not one. */
    private static String describeStack(int bound) {
        if (bound == dev.madlador.ir.StackDepth.UNBOUNDED) {
            return "unbounded (recursive)";
        }
        return "at most " + bound + " bytes";
    }
}
