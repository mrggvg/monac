package dev.madlador.regalloc;

import dev.madlador.codegen.Asm;
import dev.madlador.ir.Ir;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chaitin-Briggs graph colouring, per function body.
 *
 * <p>Simplify, then select. Nodes with fewer neighbours than there are registers are
 * pushed onto a stack and removed from the graph, which may make their neighbours
 * trivially colourable in turn. When nothing is trivial any more, a node is chosen as
 * a spill candidate and pushed optimistically — Briggs' refinement — because it often
 * still gets a colour once its neighbours have been assigned. Popping the stack
 * assigns each node a register avoiding its neighbours'.
 *
 * <p>The register file is small enough to state exactly:
 *
 * <ul>
 *   <li><b>{@code D}</b> is the frame pointer and never allocated.</li>
 *   <li><b>{@code A}</b> is the accumulator and scratch register, and is likewise
 *       never allocated — see the note on {@code REGISTERS} for why.</li>
 *   <li><b>{@code B}</b> and <b>{@code C}</b> are the colours.</li>
 * </ul>
 *
 * <p>Two colours is very tight, which is exactly why spilling has to be cheap — and
 * on this machine it is, because ALU instructions take a memory source operand, so a
 * spilled value read as an operand costs nothing at all. That is the property that
 * makes an allocator this constrained still worth having.
 */
public final class GraphColoring {

    /**
     * The colours: B and C.
     *
     * <p>{@code D} is the frame pointer, and {@code A} is deliberately <em>not</em>
     * allocated. The instruction selector needs a scratch register whenever a result
     * is spilled — memory is never an ALU destination on this machine, so the value
     * has to be computed somewhere before being stored — and which values get
     * spilled is not known until after colouring. Allocating A anyway and hoping
     * produces exactly the bug you would expect: a loop counter living in A, and an
     * {@code INC A} for an unrelated spilled temporary quietly destroying it.
     *
     * <p>Reserving A also makes the {@code MUL}/{@code DIV} constraint free rather
     * than something to work around, since the accumulator those need is never
     * holding anything else.
     *
     * <p>The alternative is Chaitin's iteration — colour, rewrite the spills, rebuild
     * the graph, repeat — which would buy back one register. At two colours versus
     * three that is a real difference, but not one worth an unsound intermediate
     * state to reach.
     */
    private static final List<Asm.Reg> REGISTERS = List.of(Asm.Reg.B, Asm.Reg.C);

    private final InterferenceGraph graph;
    private final Map<Ir.VReg, Set<Ir.VReg>> working = new LinkedHashMap<>();
    private final Map<Ir.VReg, Ir.VReg> coalescedInto = new HashMap<>();
    private final Map<Ir.VReg, Asm.Reg> colors = new LinkedHashMap<>();
    private final Set<Ir.VReg> spilled = new LinkedHashSet<>();

    public GraphColoring(InterferenceGraph graph) {
        this.graph = graph;
        run();
    }

    /** The outcome: a register for each value that got one, and the rest spilled. */
    public record Allocation(Map<Ir.VReg, Asm.Reg> registers, Set<Ir.VReg> spilled) {

        public Asm.Reg registerFor(Ir.VReg vreg) {
            return registers.get(vreg);
        }

        public boolean isSpilled(Ir.VReg vreg) {
            return spilled.contains(vreg);
        }
    }

    public Allocation allocation() {
        return new Allocation(Map.copyOf(colors), Set.copyOf(spilled));
    }

    private void run() {
        buildWorkingGraph();
        coalesce();

        Deque<Ir.VReg> stack = simplify();
        select(stack);
        resolveCoalesced();
    }

    private void buildWorkingGraph() {
        for (Ir.VReg node : graph.nodes()) {
            // Anything live across a call cannot be in a register at all.
            if (graph.mustSpill(node)) {
                spilled.add(node);
                continue;
            }
            working.put(node, new LinkedHashSet<>());
        }
        for (Ir.VReg node : working.keySet()) {
            for (Ir.VReg neighbour : graph.neighboursOf(node)) {
                if (working.containsKey(neighbour)) working.get(node).add(neighbour);
            }
        }
    }

    /**
     * Merges the two ends of a copy when they do not interfere.
     *
     * <p>The highest-value part of the allocator here. The IR is three-address and
     * the machine is two-address, so a copy precedes essentially every binary
     * operation; giving both ends the same register deletes the move outright.
     *
     * <p>Uses Briggs' conservative test — merge only if the combined node has fewer
     * than K neighbours of significant degree — so coalescing can never turn a
     * colourable graph into an uncolourable one.
     *
     * <p>Significant is the operative word. This used to count every neighbour, which
     * with only two colours refused almost any merge in a loop with more than one other
     * value live: {@code e = e + dx} kept its scratch and its copy back, three
     * instructions for one. A neighbour of degree below K will be simplified away
     * whatever happens to the merged node, so it cannot be what makes it uncolourable.
     */
    private void coalesce() {
        int limit = REGISTERS.size();
        for (InterferenceGraph.Copy copy : graph.copies()) {
            Ir.VReg destination = resolve(copy.destination());
            Ir.VReg source = resolve(copy.source());

            if (destination.equals(source)) continue;
            if (!working.containsKey(destination) || !working.containsKey(source)) continue;
            if (working.get(destination).contains(source)) continue;

            Set<Ir.VReg> merged = new LinkedHashSet<>(working.get(destination));
            merged.addAll(working.get(source));
            merged.remove(destination);
            merged.remove(source);
            int significant = 0;
            for (Ir.VReg neighbour : merged) {
                Set<Ir.VReg> around = working.get(neighbour);
                // A neighbour of both ends loses one edge when they become one node.
                int degree = around.size()
                        - (around.contains(destination) && around.contains(source) ? 1 : 0)
                        + graph.forbiddenFor(neighbour).size();
                if (degree >= limit) significant++;
            }
            // Briggs' test fails in any loop that keeps many values live, because they
            // are all of high degree. George's catches the case that matters there: a
            // temporary that lives inside a variable's range has no neighbour the
            // variable does not already have, so taking it on constrains nothing.
            if (significant >= limit && !george(destination, source, limit)
                    && !george(source, destination, limit)) {
                continue;
            }

            // Merging incompatible constraints would lose a pre-colouring.
            Set<Asm.Reg> constraints = new LinkedHashSet<>(graph.forbiddenFor(destination));
            constraints.addAll(graph.forbiddenFor(source));
            if (constraints.size() >= limit) continue;

            for (Ir.VReg neighbour : merged) {
                working.get(neighbour).remove(source);
                working.get(neighbour).add(destination);
            }
            working.put(destination, merged);
            working.remove(source);
            coalescedInto.put(source, destination);
        }
    }

    /**
     * George's test: {@code absorbed} may join {@code kept} if each of its neighbours
     * either already interferes with {@code kept} or has too few neighbours to matter.
     */
    private boolean george(Ir.VReg kept, Ir.VReg absorbed, int limit) {
        for (Ir.VReg neighbour : working.get(absorbed)) {
            if (neighbour.equals(kept) || working.get(kept).contains(neighbour)) continue;
            int degree = working.get(neighbour).size() + graph.forbiddenFor(neighbour).size();
            if (degree >= limit) return false;
        }
        return true;
    }

    private Ir.VReg resolve(Ir.VReg vreg) {
        Ir.VReg current = vreg;
        for (int i = 0; i < 32; i++) {
            Ir.VReg next = coalescedInto.get(current);
            if (next == null) return current;
            current = next;
        }
        return current;
    }

    /** Removes trivially colourable nodes, then optimistically the rest. */
    private Deque<Ir.VReg> simplify() {
        Map<Ir.VReg, Set<Ir.VReg>> remaining = new LinkedHashMap<>();
        working.forEach((node, neighbours) -> remaining.put(node, new LinkedHashSet<>(neighbours)));

        Deque<Ir.VReg> stack = new ArrayDeque<>();
        while (!remaining.isEmpty()) {
            Ir.VReg chosen = null;
            for (Map.Entry<Ir.VReg, Set<Ir.VReg>> entry : remaining.entrySet()) {
                int degree = entry.getValue().size() + graph.forbiddenFor(entry.getKey()).size();
                if (degree < REGISTERS.size()) {
                    chosen = entry.getKey();
                    break;
                }
            }
            if (chosen == null) {
                // Nothing is trivial. Pick the cheapest to spill and push it anyway;
                // it frequently still gets a colour once its neighbours are placed.
                chosen = remaining.keySet().stream()
                        .min(Comparator.comparingDouble(this::spillCost))
                        .orElseThrow();
            }

            Set<Ir.VReg> neighbours = remaining.remove(chosen);
            for (Ir.VReg neighbour : neighbours) {
                Set<Ir.VReg> set = remaining.get(neighbour);
                if (set != null) set.remove(chosen);
            }
            stack.push(chosen);
        }
        return stack;
    }

    /**
     * How reluctant we should be to spill a value: often-used values with few
     * neighbours are the worst candidates.
     */
    private double spillCost(Ir.VReg vreg) {
        int degree = Math.max(1, working.getOrDefault(vreg, Set.of()).size());
        return (double) graph.useCountOf(vreg) / degree;
    }

    private void select(Deque<Ir.VReg> stack) {
        while (!stack.isEmpty()) {
            Ir.VReg node = stack.pop();

            Set<Asm.Reg> taken = new HashSet<>(graph.forbiddenFor(node));
            for (Ir.VReg neighbour : working.getOrDefault(node, Set.of())) {
                Asm.Reg assigned = colors.get(neighbour);
                if (assigned != null) taken.add(assigned);
            }

            // A value with a preference gets it when it is free. That is the whole
            // of coalescing for an incoming register argument: colour it B and the
            // move the convention would otherwise need disappears.
            Asm.Reg chosen = null;
            Asm.Reg wanted = graph.preferredFor(node);
            if (wanted != null && !taken.contains(wanted)) {
                chosen = wanted;
            } else {
                // Otherwise, leave alone a register an uncoloured neighbour is
                // waiting for — but only while something else is free. A preference
                // is worth one instruction; spilling is worth more than that.
                Set<Asm.Reg> spokenFor = new HashSet<>();
                for (Ir.VReg neighbour : working.getOrDefault(node, Set.of())) {
                    if (colors.containsKey(neighbour)) continue;
                    Asm.Reg claim = graph.preferredFor(neighbour);
                    if (claim != null) spokenFor.add(claim);
                }
                chosen = firstFree(taken, spokenFor);
                if (chosen == null) chosen = firstFree(taken, Set.of());
            }
            if (chosen == null) {
                // The optimistic guess did not pay off, so this one goes to memory.
                spilled.add(node);
            } else {
                colors.put(node, chosen);
            }
        }
    }

    private static Asm.Reg firstFree(Set<Asm.Reg> taken, Set<Asm.Reg> alsoAvoid) {
        for (Asm.Reg register : REGISTERS) {
            if (!taken.contains(register) && !alsoAvoid.contains(register)) return register;
        }
        return null;
    }

    /** Gives every coalesced value the colour of the node it was merged into. */
    private void resolveCoalesced() {
        List<Ir.VReg> sources = new ArrayList<>(coalescedInto.keySet());
        for (Ir.VReg source : sources) {
            Ir.VReg target = resolve(source);
            Asm.Reg color = colors.get(target);
            if (color != null) {
                colors.put(source, color);
            } else {
                spilled.add(source);
            }
        }
    }
}
