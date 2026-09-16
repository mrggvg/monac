package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Who calls whom, and which functions can call themselves round again.
 *
 * <p>Two passes need this and they need the same answer, so it lives in one place.
 * {@link DeadSymbols} walks it to find what {@code main} can reach; the stack-depth
 * analysis walks it to add up frames along the deepest path. Building the edges twice
 * would be an invitation for the two to disagree about what a call is — and there are
 * three kinds, only one of which looks like one.
 *
 * <p>A label reaches the IR as a call target, as a {@link Ir.GlobalRef} in a value
 * position, or as an {@link Ir.Addr.Global} in an address. The middle one is how an
 * interrupt handler is installed: {@code __setisr(&onKey)} never calls {@code onKey},
 * and a graph that missed the edge would call it unreachable and delete it.
 */
public final class CallGraph {

    private final Map<String, Ir.Function> byLabel = new HashMap<>();
    private final Map<String, List<String>> edges = new HashMap<>();
    private final Set<String> addressTaken = new LinkedHashSet<>();

    public CallGraph(Ir.Module module) {
        for (Ir.Function function : module.functions()) {
            byLabel.put(function.label(), function);
        }
        for (Ir.Function function : module.functions()) {
            edges.put(function.label(), labelsIn(function));
            for (Ir.BasicBlock block : function.blocks()) {
                for (Ir.Instr instruction : block.instructions()) {
                    collectAddressTaken(instruction);
                }
            }
        }
        // A global's data can name a function too — a table of handlers, a pointer
        // set by an initializer — and that is as good as taking its address.
        for (Ir.Global global : module.globals()) addressTaken.addAll(global.addresses().values());
        addressTaken.retainAll(byLabel.keySet());

        // A call through a pointer can only reach a function whose address was taken
        // somewhere: nothing else can have got into the pointer, short of a cast from a
        // number. So that is where its edges go — every such function. Conservative, and
        // it keeps the stack bound a number and the interrupt check awake.
        for (Ir.Function function : module.functions()) {
            if (callsThroughPointer(function)) {
                List<String> more = new ArrayList<>(edges.get(function.label()));
                more.addAll(addressTaken);
                edges.put(function.label(), more);
            }
        }
    }

    private static boolean callsThroughPointer(Ir.Function function) {
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                if (instruction instanceof Ir.Call call && call.isIndirect()) return true;
            }
        }
        return false;
    }

    /**
     * Functions named by their address rather than called.
     *
     * <p>Which on this machine means one thing: an interrupt handler, installed with
     * {@code __setisr(&onKey)}. Nothing in the program calls it, so it appears in no
     * call chain — and yet it runs on the same stack, at a moment of the hardware's
     * choosing, on top of whatever was already there.
     */
    public Set<String> addressTakenFunctions() {
        return addressTaken;
    }

    private void collectAddressTaken(Ir.Instr instruction) {
        List<String> named = new ArrayList<>();
        collectLabels(instruction, named);
        // A call names its target first; every other label it names is a value.
        int from = instruction instanceof Ir.Call call && !call.isIndirect() ? 1 : 0;
        for (int i = from; i < named.size(); i++) addressTaken.add(named.get(i));
    }

    /** The function a label names, or null if it names a helper, global or string. */
    public Ir.Function functionAt(String label) {
        return byLabel.get(label);
    }

    /** Every label {@code function} names, in order, including non-functions. */
    public List<String> labelsFrom(String label) {
        return edges.getOrDefault(label, List.of());
    }

    /** The functions reachable from {@code entryLabel}, including it. */
    public Set<String> reachableFrom(String entryLabel) {
        Set<String> reachable = new LinkedHashSet<>();
        List<String> pending = new ArrayList<>();
        if (byLabel.containsKey(entryLabel)) {
            reachable.add(entryLabel);
            pending.add(entryLabel);
        }
        while (!pending.isEmpty()) {
            String label = pending.remove(pending.size() - 1);
            for (String called : labelsFrom(label)) {
                // A label naming no function is a runtime helper, a global or a
                // string; those are settled by the caller, once the survivors are known.
                if (!byLabel.containsKey(called) || !reachable.add(called)) continue;
                pending.add(called);
            }
        }
        return reachable;
    }

    /**
     * Every function that can reach itself again — directly, mutually, or round any
     * longer loop.
     *
     * <p>These are the only functions whose stack depth cannot be bounded, and so the
     * only ones that need checking at run time. Everything else has a depth the
     * compiler can add up.
     *
     * <p>Tarjan would give the same answer with better asymptotics. A reachability
     * test per function is O(V·E) and the largest program here has seventeen
     * functions, so the simpler thing wins.
     */
    public Set<String> functionsOnACycle() {
        Set<String> onACycle = new LinkedHashSet<>();
        for (String label : byLabel.keySet()) {
            if (reachesItself(label)) onACycle.add(label);
        }
        return onACycle;
    }

    private boolean reachesItself(String start) {
        Set<String> seen = new HashSet<>();
        List<String> pending = new ArrayList<>(callees(start));
        while (!pending.isEmpty()) {
            String label = pending.remove(pending.size() - 1);
            if (label.equals(start)) return true;
            if (!seen.add(label)) continue;
            pending.addAll(callees(label));
        }
        return false;
    }

    /** The labels from {@code label} that are functions in this module. */
    private List<String> callees(String label) {
        List<String> out = new ArrayList<>();
        for (String called : labelsFrom(label)) {
            if (byLabel.containsKey(called)) out.add(called);
        }
        return out;
    }

    /* ---------------- the three ways a label reaches the IR ---------------- */

    private static List<String> labelsIn(Ir.Function function) {
        List<String> labels = new ArrayList<>();
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                collectLabels(instruction, labels);
            }
        }
        return labels;
    }

    static void collectLabels(Ir.Instr instruction, List<String> into) {
        switch (instruction) {
            case Ir.Call c -> {
                if (c.target() != null) into.add(c.target());
                if (c.callee() != null) collectLabel(c.callee(), into);
                for (Ir.Value argument : c.args()) collectLabel(argument, into);
            }
            case Ir.Copy c -> collectLabel(c.src(), into);
            case Ir.Bin b -> {
                collectLabel(b.lhs(), into);
                collectLabel(b.rhs(), into);
            }
            case Ir.Un u -> collectLabel(u.src(), into);
            case Ir.Cmp c -> {
                collectLabel(c.lhs(), into);
                collectLabel(c.rhs(), into);
            }
            case Ir.Cbr c -> {
                collectLabel(c.lhs(), into);
                collectLabel(c.rhs(), into);
            }
            case Ir.Ret r -> collectLabel(r.value(), into);
            case Ir.Load l -> collectLabel(l.addr(), into);
            case Ir.AddrOf a -> collectLabel(a.addr(), into);
            case Ir.Store s -> {
                collectLabel(s.src(), into);
                collectLabel(s.addr(), into);
            }
            case Ir.In i -> collectLabel(i.port(), into);
            case Ir.Out o -> {
                collectLabel(o.port(), into);
                collectLabel(o.value(), into);
            }
            case Ir.TableBr t -> into.add(t.tableLabel());
            case Ir.Const ignored -> { }
            case Ir.Br ignored -> { }
            case Ir.Flag ignored -> { }
            case Ir.ArgIn ignored -> { }
        }
    }

    private static void collectLabel(Ir.Value value, List<String> into) {
        if (value instanceof Ir.GlobalRef reference) into.add(reference.label());
    }

    private static void collectLabel(Ir.Addr addr, List<String> into) {
        if (addr instanceof Ir.Addr.Global global) into.add(global.label());
    }
}
