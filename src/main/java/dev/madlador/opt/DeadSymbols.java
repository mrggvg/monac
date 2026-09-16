package dev.madlador.opt;

import dev.madlador.codegen.Runtime;
import dev.madlador.ir.Ir;
import dev.madlador.ir.Lowering;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drops whole functions, globals, strings and jump tables the program cannot reach.
 *
 * <p>Every other pass here works inside one function. This one is the only whole-module
 * pass, and it is the one that matters most for size rather than speed: the machine has
 * 4 KB of RAM shared between the program image, its data and the stack, so a helper
 * written but never called is not merely untidy — it is stack the program does not get.
 *
 * <p>Reachability starts at {@code main} and follows three kinds of edge:
 *
 * <ul>
 *   <li>a <b>call</b>, which names its target directly;
 *   <li>the <b>address of a function</b>, which is how an interrupt handler is
 *       installed — {@code __setisr(&onKey)} never calls {@code onKey}, and dropping it
 *       would leave the vector pointing at whatever followed;
 *   <li>a call to a <b>runtime helper</b>, which is an ordinary call in the IR, so
 *       signed division and the allocator are covered by the same walk.
 * </ul>
 *
 * <p>Only then are data symbols considered, over the functions that survived. That
 * order is what makes it transitive: a global read by exactly one function disappears
 * with it, and so does the string it printed.
 *
 * <p>Two module flags are recomputed rather than trusted, because both were set while
 * lowering code that may since have gone: a program whose only {@code __alloc} was in a
 * dead function no longer reserves a heap, and one whose only {@code __setisr} was
 * there no longer emits an interrupt vector.
 *
 * <p>The pass stands down entirely under {@code --no-entry}, which asks for the
 * functions as written so they can be assembled into something else. There is no
 * program to be reachable from, and every function is potentially an entry point.
 */
public final class DeadSymbols {

    private DeadSymbols() {
    }

    public static boolean run(Ir.Module module) {
        CallGraph graph = new CallGraph(module);
        Set<String> reachable = new HashSet<>(graph.reachableFrom(module.entryFunctionLabel()));
        // A global's data may name a function — a table of handlers — that no code
        // ever calls, so every function named in data is a root as well. Counting a
        // dead global's too is conservative, and costs at most the function it names.
        for (Ir.Global global : module.globals()) {
            for (String named : global.addresses().values()) {
                reachable.addAll(graph.reachableFrom(named));
            }
        }
        boolean changed = module.functions().removeIf(f -> !reachable.contains(f.label()));

        // Data is judged against what is left, so removing a function takes the
        // globals only it touched with it — and a global kept keeps whatever its own
        // data names, which may name more.
        Set<String> referenced = referencedLabels(module);
        boolean grew;
        do {
            grew = false;
            for (Ir.Global global : module.globals()) {
                if (referenced.contains(global.label())) {
                    grew |= referenced.addAll(global.addresses().values());
                }
            }
        } while (grew);
        changed |= module.globals().removeIf(g -> !referenced.contains(g.label()));
        changed |= module.strings().removeIf(s -> !referenced.contains(s.label()));
        changed |= module.jumpTables().removeIf(t -> !referenced.contains(t.label()));
        changed |= module.runtimeHelpers().retainAll(referenced);

        module.setUsesHeap(module.runtimeHelpers().contains(Runtime.ALLOC)
                || module.runtimeHelpers().contains(Runtime.FREE));
        module.setUsesInterrupts(referenced.contains(Lowering.ISR_VECTOR_LABEL));
        return changed;
    }

    /** Every label named anywhere in the module as it now stands. */
    private static Set<String> referencedLabels(Ir.Module module) {
        Set<String> referenced = new HashSet<>();
        List<String> labels = new ArrayList<>();
        for (Ir.Function function : module.functions()) {
            for (Ir.BasicBlock block : function.blocks()) {
                for (Ir.Instr instruction : block.instructions()) {
                    CallGraph.collectLabels(instruction, labels);
                }
            }
        }
        referenced.addAll(labels);
        return referenced;
    }

}
