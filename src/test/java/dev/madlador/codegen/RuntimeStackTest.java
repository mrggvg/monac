package dev.madlador.codegen;

import dev.madlador.ir.HeapStrategy;
import dev.madlador.ir.Ir;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every runtime helper's stack figure, recounted from its own assembly.
 *
 * <p>{@link Runtime#stackBytes} is what the stack bound charges for a call into
 * hand-written code, and a figure that is too small is a silent overlap: the heap is
 * sized around the bound, so the stack runs into it with nothing to say so. This walks
 * each helper's text adding up what moves {@code SP} — the return address, every push
 * and reservation, and whatever the helpers it calls cost in turn — and insists on the
 * same number. When there was one figure for all of them, {@code __mulhi} was two
 * bytes over it.
 */
@DisplayName("runtime helper stack figures")
class RuntimeStackTest {

    /** Every helper a program can reach through a call, the allocator pair aside. */
    private static final List<String> HELPERS = List.of(
            Runtime.SDIV, Runtime.SMOD, Runtime.SAR,
            Runtime.VWRITE, Runtime.VREAD, Runtime.VFILL, Runtime.WAITFRAME,
            Runtime.MULHI, Runtime.GETKEY,
            Runtime.MEMCPY, Runtime.MEMSET, Runtime.STRLEN, Runtime.STRCPY, Runtime.STRCMP,
            Runtime.SQRT, Runtime.SIN, Runtime.COS, Runtime.FIXMUL);

    /** An entry point or a table — a top-level label, as against a helper's own. */
    private static final Pattern ENTRY = Pattern.compile("\\.?RT_\\w+:");

    /** Every helper's text under one allocator, comments stripped, one instruction a line. */
    private static List<String> text(HeapStrategy.Kind kind) {
        Set<String> referenced = new HashSet<>(HELPERS);
        referenced.add(Runtime.ALLOC);
        referenced.add(Runtime.FREE);
        List<String> lines = new ArrayList<>();
        HeapStrategy strategy = HeapStrategy.forced(kind, new Ir.Module());
        for (Asm.Line line : Runtime.bodiesFor(referenced, strategy)) {
            if (!(line instanceof Asm.Raw raw)) continue;
            String text = raw.text();
            int comment = text.indexOf(';');
            if (comment >= 0) text = text.substring(0, comment);
            text = text.strip();
            if (!text.isEmpty()) lines.add(text.toUpperCase(Locale.ROOT));
        }
        return lines;
    }

    /**
     * Bytes a call to {@code label} uses, return address included, or -1 when this
     * allocator does not emit it.
     *
     * <p>A straight walk of the text, except that the depth at a label is taken from
     * the jump that reaches it, so code after an early return is still counted inside
     * its frame. A helper that ends without a {@code RET} or {@code JMP} falls into
     * the next one, which is how {@code __cos} works.
     */
    private static int measure(List<String> lines, String label) {
        int at = lines.indexOf(label.toUpperCase(Locale.ROOT) + ":");
        if (at < 0) return -1;
        int running = 2;
        int deepest = running;
        int frame = running;
        Map<String, Integer> atLabel = new HashMap<>();
        boolean reachable = true;
        for (int i = at + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.endsWith(":")) {
                if (ENTRY.matcher(line).matches() && !reachable) break;
                Integer jumpedFrom = atLabel.get(line.substring(0, line.length() - 1));
                if (jumpedFrom != null) running = jumpedFrom;
                reachable = true;
                continue;
            }
            if (!reachable) continue;
            String[] parts = line.split("\\s+", 2);
            String op = parts[0];
            String rest = parts.length > 1 ? parts[1].replace(" ", "") : "";
            switch (op) {
                case "PUSH" -> running += 2;
                case "POP" -> running -= 2;
                case "SUB" -> {
                    if (rest.startsWith("SP,")) running += Integer.parseInt(rest.substring(3));
                }
                case "ADD" -> {
                    if (rest.startsWith("SP,")) running -= Integer.parseInt(rest.substring(3));
                }
                case "MOV" -> {
                    if (rest.equals("D,SP")) frame = running;
                    if (rest.equals("SP,D")) running = frame;
                }
                case "CALL" -> {
                    int callee = measure(lines, rest);
                    assertTrue(callee > 0, () -> label + " calls " + rest + ", which is missing");
                    deepest = Math.max(deepest, running + callee);
                }
                default -> { }
            }
            if (op.startsWith("J")) atLabel.putIfAbsent(rest, running);
            deepest = Math.max(deepest, running);
            if (op.equals("RET") || op.equals("JMP") || op.equals("HLT") || op.equals("IRET")) {
                reachable = false;
            }
        }
        return deepest;
    }

    @Test
    @DisplayName("each helper is charged exactly what its assembly uses")
    void figuresMatchTheText() {
        List<String> lines = text(HeapStrategy.Kind.values()[0]);
        for (String helper : HELPERS) {
            int measured = measure(lines, helper);
            assertTrue(measured > 0, () -> helper + " is not in the emitted text");
            assertEquals(measured, Runtime.stackBytes(helper), helper);
        }
    }

    @Test
    @DisplayName("the allocator pair is charged for the deepest of the three allocators")
    void allocatorFiguresCoverEveryStrategy() {
        for (String helper : List.of(Runtime.ALLOC, Runtime.FREE)) {
            int deepest = 0;
            for (HeapStrategy.Kind kind : HeapStrategy.Kind.values()) {
                deepest = Math.max(deepest, measure(text(kind), helper));
            }
            assertEquals(deepest, Runtime.stackBytes(helper), helper);
        }
    }
}
