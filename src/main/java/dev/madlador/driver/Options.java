package dev.madlador.driver;

import dev.madlador.ir.HeapStrategy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line options for monac.
 *
 * <p>Hand-rolled rather than pulling in a CLI library: the compiler itself has no
 * runtime dependencies and the argument surface here is small enough that keeping
 * it that way costs nothing.
 */
public final class Options {

    /** Where compilation should stop. Each stage implies every stage before it. */
    public enum Stage {
        TOKENS, AST, IR, ASM
    }

    public Path input;
    public Path output;            // null => derive from input; STDOUT means "-"
    public boolean outputToStdout;

    public boolean dumpTokens;
    public boolean dumpAst;
    public boolean dumpIr;
    public boolean dumpAsm;

    public int optLevel = 1;

    /**
     * Heap size in bytes.
     *
     * <p>Zero means "decide from the source": a program that calls {@code __alloc}
     * gets {@link #HEAP_AUTO}, and one that does not gets no heap at all. Setting it
     * explicitly caps the size instead.
     */
    public int heapSize = 0;

    /**
     * Which allocator to emit, or null to work it out from the program.
     *
     * <p>There are three, and they answer to one contract. Left to itself the
     * compiler picks the fastest one a program is eligible for, which means the
     * general allocator would be reached only by programs allocating more than one
     * size — and the least-exercised of three implementations is the one that rots.
     * This is what lets the test suite run the same programs through all of them.
     *
     * <p>Forcing a strategy a program cannot use is not an error: {@code slab} on a
     * program with two object sizes has no cell size to use, and quietly gets the
     * general allocator instead.
     */
    public HeapStrategy.Kind heapStrategy = null;

    /** Sentinel for {@code --heap auto}. */
    public static final int HEAP_AUTO = -1;
    public boolean noEntry;        // emit bare functions, no entry stub (unit testing)
    public boolean stats;
    public boolean warnAll;
    public boolean warnError;

    public static final String USAGE = """
            usage: monac <input.mona> [options]

              -o <file>        write assembly to <file> ("-" for stdout)
              --dump-tokens    print the token stream
              --dump-ast       print the parsed syntax tree
              --dump-ir        print the intermediate representation
              --dump-asm       print the generated assembly to stdout
              -O0 | -O1 | -O2  optimization level (default -O1)
              --heap-strategy <s>  force bump, slab, bitmap; default auto
              --heap <n|auto>  cap the heap at n bytes; by default a program that
                               allocates gets all the room left over, and one that
                               does not gets no heap
              --no-entry       omit the entry stub; emit bare functions only
              --stats          report instruction count and image size
              -Wall            enable additional warnings
              -Werror          treat warnings as errors
              -h, --help       show this message
            """;

    /** Thrown for a malformed command line; the message is user-facing. */
    public static final class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }

    public static Options parse(String[] args) {
        Options o = new Options();
        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "-h", "--help" -> throw new UsageException(null);
                case "-o" -> {
                    if (++i >= args.length) throw new UsageException("-o requires a file argument");
                    if (args[i].equals("-")) {
                        o.outputToStdout = true;
                    } else {
                        o.output = Path.of(args[i]);
                    }
                }
                case "--dump-tokens" -> o.dumpTokens = true;
                case "--dump-ast" -> o.dumpAst = true;
                case "--dump-ir" -> o.dumpIr = true;
                case "--dump-asm" -> o.dumpAsm = true;
                case "-O0" -> o.optLevel = 0;
                case "-O1" -> o.optLevel = 1;
                case "-O2" -> o.optLevel = 2;
                case "--heap" -> {
                    if (++i >= args.length) throw new UsageException("--heap requires a size");
                    String value = args[i];
                    if (value.equals("auto")) {
                        o.heapSize = HEAP_AUTO;
                    } else {
                        try {
                            o.heapSize = Integer.parseInt(value);
                        } catch (NumberFormatException e) {
                            throw new UsageException("--heap wants a byte count or 'auto', got " + value);
                        }
                        if (o.heapSize < 0) throw new UsageException("--heap cannot be negative");
                    }
                }
                case "--heap-strategy" -> {
                    if (++i >= args.length) {
                        throw new UsageException("--heap-strategy requires a name");
                    }
                    o.heapStrategy = switch (args[i]) {
                        case "auto" -> null;
                        case "bump" -> HeapStrategy.Kind.BUMP;
                        case "slab" -> HeapStrategy.Kind.SLAB;
                        case "bitmap" -> HeapStrategy.Kind.BITMAP;
                        default -> throw new UsageException(
                                "--heap-strategy wants bump, slab, bitmap or auto, got " + args[i]);
                    };
                }
                case "--no-entry" -> o.noEntry = true;
                case "--stats" -> o.stats = true;
                case "-Wall" -> o.warnAll = true;
                case "-Werror" -> o.warnError = true;
                default -> {
                    if (a.startsWith("-") && a.length() > 1) {
                        throw new UsageException("unknown option: " + a);
                    }
                    positional.add(a);
                }
            }
        }

        if (positional.isEmpty()) throw new UsageException("no input file");
        if (positional.size() > 1) {
            throw new UsageException("expected one input file, got " + positional.size()
                    + " (" + String.join(", ", positional) + ")");
        }
        o.input = Path.of(positional.get(0));

        // Default output: the input path with its extension replaced by .asm.
        if (o.output == null && !o.outputToStdout) {
            String name = o.input.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0) ? name.substring(0, dot) : name;
            Path parent = o.input.getParent();
            o.output = (parent == null) ? Path.of(base + ".asm") : parent.resolve(base + ".asm");
        }
        return o;
    }

    /**
     * The last stage that needs to run. Dump options short-circuit compilation so
     * that, for example, {@code --dump-tokens} still works on a program the parser
     * would reject.
     */
    public Stage finalStage() {
        if (dumpTokens) return Stage.TOKENS;
        if (dumpAst) return Stage.AST;
        if (dumpIr) return Stage.IR;
        return Stage.ASM;
    }
}
