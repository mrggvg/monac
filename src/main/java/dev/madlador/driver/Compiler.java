package dev.madlador.driver;

import dev.madlador.ir.HeapStrategy;
import dev.madlador.codegen.ModuleEmitter;
import dev.madlador.diag.CompileException;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.diag.Span;
import dev.madlador.ir.InterruptSafety;
import dev.madlador.ir.Ir;
import dev.madlador.ir.IrPrinter;
import dev.madlador.ir.Lowering;
import dev.madlador.lexer.Lexer;
import dev.madlador.opt.PassManager;
import dev.madlador.lexer.Token;
import dev.madlador.parser.Parser;
import dev.madlador.parser.ast.AstPrinter;
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.Program;
import dev.madlador.parser.ast.TopLevel;
import dev.madlador.sema.Analyzer;
import dev.madlador.sema.Mangler;
import dev.madlador.sema.SemanticInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The compilation pipeline, in one place.
 *
 * <pre>
 *   lex -&gt; parse -&gt; analyze -&gt; lower to IR -&gt; select instructions -&gt; emit
 * </pre>
 *
 * <p>Instruction selection deliberately runs before any register assignment: only
 * once operands have been folded into memory is it clear which values actually need
 * to be held somewhere, and on a machine with four registers that distinction is the
 * whole game.
 */
public final class Compiler {

    private final DiagnosticReporter reporter;
    private final SourceFile source;

    public Compiler(SourceFile source, DiagnosticReporter reporter) {
        this.source = source;
        this.reporter = reporter;
    }

    /**
     * What a run produced. Later stages are null when an earlier one stopped.
     *
     * @param imageBytes how much RAM the program occupies once loaded, or 0 if code
     *                   generation did not run. The machine has 4096 bytes for the
     *                   image, its data and the stack together, so this is the number
     *                   that decides whether a program fits.
     * @param stackBound the most stack the program can use, or
     *                   {@link dev.madlador.ir.StackDepth#UNBOUNDED} where a recursive
     *                   call chain means there is no such number
     */
    public record Result(List<Token> tokens, Program ast, Ir.Module ir, String assembly,
                         int imageBytes, int stackBound) {
    }

    public Result compile(Options.Stage stopAfter, boolean withEntry) {
        return compile(stopAfter, withEntry, 1);
    }

    public Result compile(Options.Stage stopAfter, boolean withEntry, int optLevel) {
        return compile(stopAfter, withEntry, optLevel, 0);
    }

    public Result compile(Options.Stage stopAfter, boolean withEntry, int optLevel, int heapSize) {
        return compile(stopAfter, withEntry, optLevel, heapSize, null);
    }

    /**
     * @param heapStrategy which allocator to emit, or null to choose from the program
     */
    public Result compile(Options.Stage stopAfter, boolean withEntry, int optLevel,
                          int heapSize, HeapStrategy.Kind heapStrategy) {
        // The lexer recovers from bad characters, so parsing proceeds regardless and
        // one run reports lexical and syntactic problems together.
        List<Token> tokens = new Lexer(source, reporter).tokenize();
        if (stopAfter == Options.Stage.TOKENS) {
            return new Result(tokens, null, null, null, 0, 0);
        }

        Program ast = new Parser(tokens, reporter).parseProgram();
        if (stopAfter == Options.Stage.AST) {
            return new Result(tokens, ast, null, null, 0, 0);
        }
        if (reporter.hasErrors()) {
            // Analysing a tree with holes in it produces cascading nonsense.
            throw new CompileException("stopping after syntax errors");
        }

        Mangler mangler = new Mangler();
        SemanticInfo info = new Analyzer(reporter, mangler).analyze(ast);
        if (reporter.hasErrors()) {
            throw new CompileException("stopping after semantic errors");
        }

        Ir.Module ir = new Lowering(info, reporter, mangler).lower(ast);

        PassManager.forLevel(optLevel).run(ir, withEntry);

        // A program that allocates gets a heap without being asked to request one:
        // the need is obvious from the source, so making the user pass a flag would
        // only be a way to get it wrong. An explicit --heap still caps the size for
        // anyone who wants a hard bound. Asked after the optimizer, since a program
        // whose only __alloc was in unreachable code does not allocate after all.
        int heap = (heapSize == 0 && ir.usesHeap()) ? Options.HEAP_AUTO : heapSize;

        // Asked after the optimizer for the same reason: a handler that allocates in
        // code nothing can reach is not a hazard, and reachability is what says so.
        InterruptSafety.check(ir, functionSpans(ast), reporter);

        if (stopAfter == Options.Stage.IR) {
            return new Result(tokens, ast, ir, null, 0, 0);
        }
        if (reporter.hasErrors()) {
            throw new CompileException("stopping before code generation");
        }

        ModuleEmitter emitter = new ModuleEmitter(reporter, mangler);
        String assembly = emitter.emit(ir, withEntry, optLevel, heap, heapStrategy);
        return new Result(tokens, ast, ir, assembly,
                emitter.imageBytes(), emitter.stackBound());
    }

    /**
     * Where each function was declared, by name, so an IR-level check can point at
     * source. The IR carries labels and names but no spans; the tree has both.
     */
    private static Map<String, Span> functionSpans(Program ast) {
        Map<String, Span> spans = new HashMap<>();
        for (TopLevel item : ast.items()) {
            if (item instanceof FunctionDefinition definition) {
                spans.put(definition.name(), definition.span());
            }
        }
        return spans;
    }

    /** Renders whichever stage the options asked for. */
    public static String describe(Result result, Options.Stage stage) {
        return switch (stage) {
            case TOKENS -> {
                StringBuilder sb = new StringBuilder();
                result.tokens().forEach(t -> sb.append(t).append('\n'));
                yield sb.toString();
            }
            case AST -> AstPrinter.print(result.ast());
            case IR -> IrPrinter.print(result.ir());
            case ASM -> result.assembly();
        };
    }
}
