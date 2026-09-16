package dev.madlador.ir;

import dev.madlador.codegen.Runtime;
import dev.madlador.opt.CallGraph;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * How deep the stack can get, worked out before a single instruction is emitted.
 *
 * <p>The machine has 4096 bytes shared between the image, the heap and the stack, and
 * no memory protection: a stack that grows too far writes over the program and the
 * first sign of it is {@code Invalid opcode}. So it is worth knowing in advance, and
 * for most programs it is knowable — a call graph with no cycle in it has a longest
 * path, and the frames along that path are a number.
 *
 * <p>What a call costs the stack, all of it visible in the IR:
 *
 * <pre>
 *   depth(f) = 2                             its return address, pushed by its caller
 *            + 2                             the saved frame pointer
 *            + 2 * slotCount(f)              its locals
 *            + max over the calls f makes of
 *                ( 2 * (arguments - 1)       pushed; argument 0 travels in B
 *                + depth(callee) )
 * </pre>
 *
 * <p>A callee's return address is counted in the callee, not at the call site, so that
 * every frame is described in one place and nothing is added twice.
 *
 * <p>The saved frame pointer is counted even though
 * {@code FramePointerElimination} usually removes it, because whether it does is not
 * known until after the code exists. Over-counting the stack only costs heap, and by
 * two bytes a frame; under-counting it would cost correctness.
 *
 * <p>Recursion has no longest path, so a function that can reach itself is reported
 * as unbounded — and those are exactly the functions worth checking at run time,
 * which is the other thing this hands back.
 *
 * <p>An interrupt handler is added on top rather than folded in. It is not on any
 * call chain — nothing calls it; the hardware does — so it can arrive at the program's
 * deepest moment and start again from there. What it costs is the CPU's own IP and SR,
 * the four registers the trampoline saves, the call into the handler, and then the
 * handler's own chain.
 */
public final class StackDepth {

    /** Bytes a frame costs beyond its locals: the return address and the saved D. */
    private static final int FRAME_OVERHEAD = 4;

    /**
     * What reaching a handler costs before the handler's own frame.
     *
     * <p>{@code IP} and {@code SR} pushed by the CPU, then {@code A}, {@code B},
     * {@code C} and {@code D} pushed by the trampoline: six words.
     */
    private static final int INTERRUPT_OVERHEAD = 12;

    private final CallGraph graph;
    private final Set<String> onACycle;
    private final String entryLabel;
    private final Map<String, Integer> depths = new HashMap<>();
    private final int bound;

    public StackDepth(Ir.Module module) {
        this.graph = new CallGraph(module);
        this.onACycle = graph.functionsOnACycle();
        this.entryLabel = module.entryFunctionLabel();

        // main's own frame already counts the return address the entry stub's
        // CALL leaves behind, so there is nothing to add on top.
        int main = graph.functionAt(entryLabel) == null ? 0 : depthOf(entryLabel);
        int handler = module.usesInterrupts() ? interruptDepth() : 0;
        this.bound = (main == UNBOUNDED || handler == UNBOUNDED)
                ? UNBOUNDED
                : main + handler;
    }

    /**
     * What a call through a pointer costs: the deepest function it could reach.
     *
     * <p>Only a function whose address was taken can be in a pointer, and the call
     * graph gives an indirect call an edge to every one of them, so a cycle through a
     * pointer is found like any other. A call whose target copy propagation pinned down
     * costs exactly that function. With no function's address taken at all, the
     * pointer was made from a number, and nothing is known about where it goes.
     */
    private int indirectDepth(Ir.Call call) {
        if (call.callee() instanceof Ir.GlobalRef ref && graph.functionAt(ref.label()) != null) {
            return depthOf(ref.label());
        }
        if (graph.addressTakenFunctions().isEmpty()) return UNBOUNDED;
        int deepest = 0;
        for (String label : graph.addressTakenFunctions()) {
            int depth = depthOf(label);
            if (depth == UNBOUNDED) return UNBOUNDED;
            deepest = Math.max(deepest, depth);
        }
        return deepest;
    }

    /** A depth that cannot be computed, because the path through it has no end. */
    public static final int UNBOUNDED = -1;

    /** The most stack the program can use, in bytes, or {@link #UNBOUNDED}. */
    public int bound() {
        return bound;
    }

    public boolean isBounded() {
        return bound != UNBOUNDED;
    }

    /**
     * The functions that can reach themselves again.
     *
     * <p>The only ones whose depth is not a number, and so the only ones where a
     * run-time check earns the two instructions it costs.
     */
    public Set<String> recursiveFunctions() {
        return onACycle;
    }

    /**
     * The deepest chain of calls that contains no recursive function.
     *
     * <p>This is the margin a run-time check needs. Checks are emitted only in
     * recursive functions, so between one check and the next the stack can still grow
     * by a whole acyclic chain — and the check has to fire before that growth reaches
     * the floor rather than after.
     */
    public int acyclicMargin() {
        int deepest = 0;
        for (String label : graph.reachableFrom(entryLabel)) {
            if (onACycle.contains(label)) continue;
            int depth = depthOf(label);
            if (depth != UNBOUNDED) deepest = Math.max(deepest, depth);
        }
        return deepest + FRAME_OVERHEAD;
    }

    /**
     * What an interrupt adds, on top of however deep the program already is.
     *
     * <p>Zero when no handler can be installed. Otherwise the trampoline's own cost
     * plus the deepest handler, because {@code __setisr} can point the vector at any
     * of them and which one is a run-time decision.
     */
    private int interruptDepth() {
        int deepest = 0;
        for (String label : graph.addressTakenFunctions()) {
            int depth = depthOf(label);
            if (depth == UNBOUNDED) return UNBOUNDED;
            deepest = Math.max(deepest, depth);
        }
        return INTERRUPT_OVERHEAD + deepest;
    }

    /* ---------------- the walk ---------------- */

    private int depthOf(String label) {
        if (onACycle.contains(label)) return UNBOUNDED;

        Integer memo = depths.get(label);
        if (memo != null) return memo;

        Ir.Function function = graph.functionAt(label);
        if (function == null) {
            // A runtime helper. Hand-written, so its figure is counted from its text.
            int helper = Runtime.stackBytes(label);
            depths.put(label, helper);
            return helper;
        }

        int own = 2 * function.slotCount() + FRAME_OVERHEAD;
        int deepestCall = 0;
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                if (!(instruction instanceof Ir.Call call)) continue;
                int callee = call.isIndirect() ? indirectDepth(call) : depthOf(call.target());
                if (callee == UNBOUNDED) {
                    depths.put(label, UNBOUNDED);
                    return UNBOUNDED;
                }
                int pushed = 2 * Math.max(0, call.args().size() - 1);
                deepestCall = Math.max(deepestCall, pushed + callee);
            }
        }
        int total = own + deepestCall;
        depths.put(label, total);
        return total;
    }
}
