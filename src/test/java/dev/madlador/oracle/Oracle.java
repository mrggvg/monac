package dev.madlador.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client for the headless simulator oracle ({@code monasim.js}).
 *
 * <p>This drives the <em>real</em> assembler and CPU out of the simulator's own
 * bundle, so a passing end-to-end test is evidence about the actual target machine
 * rather than about our understanding of it.
 *
 * <p>One long-lived {@code node} process is shared by the whole JVM: startup costs
 * roughly 200&nbsp;ms, which is worth paying once rather than per test class.
 */
public final class Oracle implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_MAX_STEPS = 200_000;

    private static Oracle shared;
    private static boolean sharedFailed;

    private final Process process;
    private final BufferedWriter in;
    private final BufferedReader out;

    private Oracle(Process process) {
        this.process = process;
        this.in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /* ---------------- lifecycle ---------------- */

    /** The node binary to use: {@code -Dmonac.node=...}, {@code $MONAC_NODE}, else {@code node}. */
    private static String nodeCommand() {
        String p = System.getProperty("monac.node");
        if (p != null && !p.isBlank()) return p;
        String e = System.getenv("MONAC_NODE");
        if (e != null && !e.isBlank()) return e;
        return "node";
    }

    private static Path scriptPath() {
        URL url = Oracle.class.getClassLoader().getResource("oracle/monasim.js");
        if (url == null) throw new IllegalStateException("oracle/monasim.js is not on the test classpath");
        try {
            return Path.of(url.toURI());
        } catch (Exception e) {
            throw new IllegalStateException("cannot resolve monasim.js path", e);
        }
    }

    /**
     * Whether end-to-end testing is possible here. Tests should gate on this with
     * {@code Assumptions.assumeTrue(...)} so a machine without Node skips rather
     * than fails.
     */
    public static synchronized boolean isAvailable() {
        if (sharedFailed) return false;
        try {
            get();
            return true;
        } catch (RuntimeException e) {
            sharedFailed = true;
            return false;
        }
    }

    /** The reason the oracle is unavailable, for a useful skip message. */
    public static String unavailableReason() {
        return "Node oracle unavailable (need `" + nodeCommand()
                + "` on PATH and the asm-sim bundle reachable); set -Dmonac.node=<path> to override";
    }

    public static synchronized Oracle get() {
        if (shared != null && shared.process.isAlive()) return shared;
        shared = start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                shared.close();
            } catch (Exception ignored) {
                // best effort on JVM shutdown
            }
        }));
        return shared;
    }

    private static Oracle start() {
        Path script = scriptPath();
        if (!Files.isReadable(script)) throw new IllegalStateException("cannot read " + script);
        ProcessBuilder pb = new ProcessBuilder(nodeCommand(), script.toString());
        pb.redirectErrorStream(false);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("cannot start " + nodeCommand() + ": " + e.getMessage(), e);
        }
        Oracle oracle = new Oracle(proc);

        // The server announces itself before accepting work; a failure to load the
        // simulator bundle is reported on that same line.
        String hello;
        try {
            hello = oracle.out.readLine();
        } catch (IOException e) {
            proc.destroyForcibly();
            throw new IllegalStateException("oracle handshake failed", e);
        }
        if (hello == null) {
            proc.destroyForcibly();
            throw new IllegalStateException("oracle produced no output (is node installed?)");
        }
        JsonNode node = parse(hello);
        if (!node.path("ok").asBoolean(false)) {
            proc.destroyForcibly();
            throw new IllegalStateException("oracle init failed: " + node.path("error").asText());
        }
        return oracle;
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing stdin is what asks the server to exit
        }
        process.destroy();
    }

    /* ---------------- requests ---------------- */

    public Result run(String assembly) {
        return run(assembly, DEFAULT_MAX_STEPS, null);
    }

    /**
     * Assembles without running, for when only {@link Result#imageSize()} is wanted.
     *
     * <p>Running is not always an option: a program that touches a port faults unless
     * the peripherals are attached, and how big it is has nothing to do with that.
     */
    public synchronized Result assemble(String assembly) {
        ObjectNode req = JSON.createObjectNode();
        req.put("asm", assembly);
        req.put("assembleOnly", true);
        return exchange(req, assembly);
    }

    public Result run(String assembly, int maxSteps) {
        return run(assembly, maxSteps, null);
    }

    /**
     * Assembles and executes {@code assembly}, stopping at {@code HLT}, at a fault,
     * or after {@code maxSteps} instructions.
     *
     * @param dumpMem optional {@code [start, end)} byte range to read back, or null
     */
    public synchronized Result run(String assembly, int maxSteps, int[] dumpMem) {
        return run(assembly, maxSteps, dumpMem, null);
    }

    /**
     * As above, but also attaches the peripherals that live on I/O ports — graphics,
     * keypad, timer and random generator — and optionally reads back a range of
     * video memory.
     *
     * <p>They are opt-in because they are not free: the graphics card runs a refresh
     * timer, and most programs never touch a port.
     *
     * @param dumpVram optional {@code [start, end)} VRAM byte range, or null
     */
    public synchronized Result run(String assembly, int maxSteps, int[] dumpMem, int[] dumpVram) {
        return run(assembly, maxSteps, dumpMem, dumpVram, null, 0);
    }

    /**
     * As above, but also delivers keystrokes while the program runs.
     *
     * <p>They arrive during execution rather than before it: a keyboard interrupt is
     * only raised once the program has set the keyboard's bit in IRQMASK, which has
     * not happened at step zero.
     *
     * @param keys      characters to press, or null
     * @param keyEvery  press one every this many instructions
     */
    public synchronized Result run(String assembly, int maxSteps, int[] dumpMem, int[] dumpVram,
                                   String keys, int keyEvery) {
        return run(assembly, maxSteps, dumpMem, dumpVram, keys, keyEvery, keys != null);
    }

    /**
     * As above, with the peripherals attached whether or not any key is pressed.
     *
     * <p>The timer needs this: a program driven by timer interrupts touches ports
     * without ever reading the keypad, and without the components attached those
     * ports are not registered at all, so {@code OUT 3} faults.
     */
    public synchronized Result run(String assembly, int maxSteps, int[] dumpMem, int[] dumpVram,
                                   String keys, int keyEvery, boolean peripherals) {
        ObjectNode req = JSON.createObjectNode();
        req.put("asm", assembly);
        req.put("maxSteps", maxSteps);
        if (peripherals) req.put("peripherals", true);
        if (keys != null) {
            req.put("keys", keys);
            if (keyEvery > 0) req.put("keyEvery", keyEvery);
        }
        if (dumpMem != null) {
            if (dumpMem.length != 2) throw new IllegalArgumentException("dumpMem must be [start, end]");
            req.putArray("dumpMem").add(dumpMem[0]).add(dumpMem[1]);
        }
        if (dumpVram != null) {
            if (dumpVram.length != 2) throw new IllegalArgumentException("dumpVram must be [start, end]");
            req.putArray("dumpVram").add(dumpVram[0]).add(dumpVram[1]);
        }
        return exchange(req, assembly);
    }

    /**
     * As {@link #run(String, int, int[], int[], String, int)}, but each key is pressed
     * <em>and released</em> — the two events the hardware actually delivers.
     *
     * <p>KBDSTATUS is a bit field, not a flag: 1 is a key going down, 2 is one coming
     * up, and 4 is added when a previous event was never collected. Sending only
     * key-downs makes a whole class of polling bug invisible, because code that tests
     * for 1 and returns early never drains KBDDATA on the release and latches the port.
     */
    public synchronized Result runWithKeyUp(String assembly, int maxSteps,
                                            String keys, int keyEvery) {
        ObjectNode req = JSON.createObjectNode();
        req.put("asm", assembly);
        req.put("maxSteps", maxSteps);
        req.put("peripherals", true);
        req.put("keys", keys);
        if (keyEvery > 0) req.put("keyEvery", keyEvery);
        req.put("keyUp", true);
        return exchange(req, assembly);
    }

    /**
     * Runs with every peripheral attached and reads back a rectangle of the pixels the
     * graphics card actually drew, row-major, each one packed as {@code 0xRRGGBB}.
     *
     * <p>This is the only way to test tile mode. In bitmap mode video memory is the
     * screen, so {@link #run(String, int, int[], int[])} and its {@code dumpVram} tell
     * you everything; in tile mode they tell you nothing, because the card composes
     * 17&times;17 tiles, a pixel-granular scroll offset, a background colour and eight
     * sprites into the canvas, and none of that composition is visible in the tile map
     * a program wrote.
     *
     * <p>A rectangle rather than the whole screen: 65,536 pixels is half a megabyte of
     * JSON, and an assertion wants a handful.
     */
    public synchronized Result runScreen(String assembly, int maxSteps,
                                         int x, int y, int width, int height) {
        ObjectNode req = JSON.createObjectNode();
        req.put("asm", assembly);
        req.put("maxSteps", maxSteps);
        req.put("peripherals", true);
        req.putArray("dumpScreen").add(x).add(y).add(width).add(height);
        return exchange(req, assembly);
    }

    /** One request, one response line. */
    private Result exchange(ObjectNode req, String assembly) {
        String line;
        try {
            in.write(JSON.writeValueAsString(req));
            in.write('\n');
            in.flush();
            line = out.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("oracle communication failed", e);
        }
        if (line == null) throw new IllegalStateException("oracle closed the connection unexpectedly");
        return Result.from(parse(line), assembly);
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (IOException e) {
            throw new IllegalStateException("malformed oracle response: " + line, e);
        }
    }

    /* ---------------- result ---------------- */

    /** Outcome of one assemble-and-run request. */
    public record Result(
            boolean ok,
            String phase,
            String error,
            Integer errorLine,
            int steps,
            boolean halted,
            boolean timedOut,
            boolean fault,
            Map<String, Integer> registers,
            int statusRegister,
            int imageSize,
            int[] memory,
            int[] videoMemory,
            int[] screen,
            String assembly) {

        static Result from(JsonNode n, String assembly) {
            Map<String, Integer> regs = new LinkedHashMap<>();
            JsonNode r = n.path("regs");
            r.fieldNames().forEachRemaining(name -> regs.put(name, r.path(name).asInt()));

            int[] mem = readBytes(n, "mem");
            int[] vram = readBytes(n, "vram");
            int[] screen = readBytes(n, "screen");
            return new Result(
                    n.path("ok").asBoolean(false),
                    n.path("phase").asText(null),
                    n.path("error").asText(null),
                    n.hasNonNull("line") ? n.get("line").asInt() : null,
                    n.path("steps").asInt(),
                    n.path("halted").asBoolean(false),
                    n.path("timedOut").asBoolean(false),
                    n.path("fault").asBoolean(false),
                    regs,
                    n.path("sr").asInt(),
                    n.path("size").asInt(),
                    mem,
                    vram,
                    screen,
                    assembly);
        }

        private static int[] readBytes(JsonNode n, String field) {
            if (!n.has(field) || !n.get(field).isArray()) return null;
            JsonNode array = n.get(field);
            int[] bytes = new int[array.size()];
            for (int i = 0; i < array.size(); i++) bytes[i] = array.get(i).asInt();
            return bytes;
        }

        /**
         * Whether the assembler accepted the program, regardless of what happened
         * when it ran. Use this to test operand forms: a snippet like {@code DIV [D-3]}
         * assembles perfectly well but faults at address -3 when D is still zero.
         */
        public boolean assembled() {
            return ok || !"assemble".equals(phase);
        }

        public int a() { return reg("A"); }
        public int b() { return reg("B"); }
        public int c() { return reg("C"); }
        public int d() { return reg("D"); }
        public int sp() { return reg("SP"); }

        public int reg(String name) {
            Integer v = registers.get(name);
            if (v == null) throw new IllegalStateException("no register " + name + " in " + this);
            return v;
        }

        /** A multi-line description including the assembly, for failure messages. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            if (!ok) {
                sb.append("oracle ").append(phase).append(" error: ").append(error);
                if (errorLine != null) sb.append(" (line ").append(errorLine).append(')');
            } else {
                sb.append("steps=").append(steps)
                        .append(" halted=").append(halted)
                        .append(" fault=").append(fault)
                        .append(" size=").append(imageSize)
                        .append(' ').append(registers);
            }
            sb.append("\n--- assembly ---\n");
            String[] lines = assembly.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                sb.append(String.format("%4d | %s%n", i + 1, lines[i]));
            }
            return sb.toString();
        }
    }
}
