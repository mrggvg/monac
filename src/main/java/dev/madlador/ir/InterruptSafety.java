package dev.madlador.ir;

import dev.madlador.codegen.Runtime;
import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.Span;
import dev.madlador.opt.CallGraph;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Allocating from an interrupt handler and from the program at the same time.
 *
 * <p>None of the allocators is re-entrant, and none of them can be made so cheaply.
 * Every one of them reads a word, decides something from it, and writes it back —
 * the bump allocator loads the frontier, adds, and stores; the others walk a hint and
 * a bitmap word. An interrupt arriving between the load and the store leaves the
 * handler working from the value the interrupted code was about to change, and both
 * are handed the same memory. Two live objects then share an address, which is the
 * kind of bug that shows up as data going wrong somewhere else entirely.
 *
 * <p>Reproduced, not theorised: a program allocating in a loop with a timer handler
 * that also allocates gets the same pointer in both within a few thousand
 * instructions.
 *
 * <p>The check is exact rather than cautious, because the machine masks interrupts for
 * the duration of a handler — {@code toInterruptHandler} clears {@code SR.irqMask} and
 * {@code IRET} restores it — so a handler cannot interrupt itself. One side allocating
 * is therefore safe: it takes allocation on the handler side *and* on the program side
 * for the two to overlap, and that is what this looks for.
 *
 * <p>A warning and not an error: disabling interrupts around the program's own
 * allocations makes it safe again, and that is the fix — {@code __cli()} before and
 * {@code __sti()} after. Doing that inside the allocator would cost every program two
 * instructions per call and would silently re-enable interrupts for one that had
 * turned them off deliberately, so it belongs at the call site, where the program
 * knows what it wants.
 */
public final class InterruptSafety {

    private InterruptSafety() {
    }

    /**
     * Warns if the heap can be entered from an interrupt handler and the program at
     * once.
     *
     * @param spans function name to where it was declared, for the diagnostic
     */
    public static void check(Ir.Module module, Map<String, Span> spans,
                             DiagnosticReporter reporter) {
        if (!module.usesInterrupts()) return;

        CallGraph graph = new CallGraph(module);
        Set<String> handlerSide = new LinkedHashSet<>();
        for (String handler : graph.addressTakenFunctions()) {
            handlerSide.addAll(graph.reachableFrom(handler));
        }
        if (handlerSide.isEmpty()) return;

        // Whatever a handler can also reach is not the program side: it is shared, and
        // the handler cannot interrupt itself, so it is only the code the handler
        // cannot be running that can be interrupted by it.
        Set<String> programSide = new LinkedHashSet<>(
                graph.reachableFrom(module.entryFunctionLabel()));
        programSide.removeAll(handlerSide);

        Set<String> fromHandler = allocatingFunctions(graph, handlerSide);
        if (fromHandler.isEmpty() || allocatingFunctions(graph, programSide).isEmpty()) {
            return;
        }

        // Sorted, so the same program always reports the same function first.
        for (String name : new TreeSet<>(fromHandler)) {
            reporter.warning(spans.getOrDefault(name, Span.NONE),
                    "'" + name + "' allocates and can run inside an interrupt handler,"
                            + " while the program allocates too",
                    "the allocator is not re-entrant: an interrupt arriving inside"
                            + " __alloc can hand the same memory to both. Disable"
                            + " interrupts around the program's allocations with __cli()"
                            + " and __sti(), or do not allocate in the handler");
        }
    }

    /** Which of these functions call into the heap at all. */
    private static Set<String> allocatingFunctions(CallGraph graph, Set<String> labels) {
        Set<String> found = new LinkedHashSet<>();
        for (String label : labels) {
            Ir.Function function = graph.functionAt(label);
            if (function == null) continue;             // a runtime helper, not ours
            for (Ir.BasicBlock block : function.blocks()) {
                for (Ir.Instr instruction : block.instructions()) {
                    if (instruction instanceof Ir.Call call
                            && (Runtime.ALLOC.equals(call.target())
                                || Runtime.FREE.equals(call.target()))) {
                        found.add(function.name());
                    }
                }
            }
        }
        return found;
    }
}
