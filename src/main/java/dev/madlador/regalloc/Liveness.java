package dev.madlador.regalloc;

import dev.madlador.ir.Ir;
import dev.madlador.opt.Uses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which virtual registers are live where.
 *
 * <p>Backward dataflow to a fixpoint over the control-flow graph:
 * {@code liveOut[b] = union of liveIn[successors]} and
 * {@code liveIn[b] = use[b] union (liveOut[b] minus def[b])}. Straight-line code
 * settles in one round; loops are exactly why the iteration is needed, since a value
 * assigned at the bottom of a loop body is live at the top on the next pass.
 *
 * <p>Getting loops right here is what makes the interference graph correct. A value
 * carried around a loop interferes with everything else in the body, and missing
 * that produces an allocation that looks fine and corrupts on the second iteration.
 */
public final class Liveness {

    private final Ir.Function function;
    private final Map<String, Ir.BasicBlock> byLabel = new HashMap<>();
    private final Map<Ir.BasicBlock, Set<Ir.VReg>> liveIn = new HashMap<>();
    private final Map<Ir.BasicBlock, Set<Ir.VReg>> liveOut = new HashMap<>();

    public Liveness(Ir.Function function) {
        this.function = function;
        for (Ir.BasicBlock block : function.blocks()) byLabel.put(block.label(), block);
        compute();
    }

    private void compute() {
        Map<Ir.BasicBlock, Set<Ir.VReg>> used = new HashMap<>();
        Map<Ir.BasicBlock, Set<Ir.VReg>> defined = new HashMap<>();

        for (Ir.BasicBlock block : function.blocks()) {
            Set<Ir.VReg> use = new LinkedHashSet<>();
            Set<Ir.VReg> def = new LinkedHashSet<>();
            for (Ir.Instr instruction : block.instructions()) {
                // A use only counts for the block if it precedes any definition here.
                for (Ir.Value value : Uses.of(instruction)) {
                    if (value instanceof Ir.VReg vreg && !def.contains(vreg)) use.add(vreg);
                }
                Ir.VReg definition = Uses.definitionOf(instruction);
                if (definition != null) def.add(definition);
            }
            used.put(block, use);
            defined.put(block, def);
            liveIn.put(block, new LinkedHashSet<>());
            liveOut.put(block, new LinkedHashSet<>());
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            // Backwards over the block list converges faster than forwards.
            List<Ir.BasicBlock> blocks = function.blocks();
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Ir.BasicBlock block = blocks.get(i);

                Set<Ir.VReg> out = new LinkedHashSet<>();
                for (Ir.BasicBlock successor : successorsOf(block)) {
                    out.addAll(liveIn.get(successor));
                }

                Set<Ir.VReg> in = new LinkedHashSet<>(out);
                in.removeAll(defined.get(block));
                in.addAll(used.get(block));

                if (!out.equals(liveOut.get(block)) || !in.equals(liveIn.get(block))) {
                    liveOut.put(block, out);
                    liveIn.put(block, in);
                    changed = true;
                }
            }
        }
    }

    public List<Ir.BasicBlock> successorsOf(Ir.BasicBlock block) {
        List<Ir.BasicBlock> result = new ArrayList<>(2);
        for (Ir.Instr instruction : block.instructions()) {
            switch (instruction) {
                case Ir.Br br -> add(result, br.target());
                case Ir.Cbr cbr -> {
                    add(result, cbr.ifTrue());
                    add(result, cbr.ifFalse());
                }
                case Ir.TableBr table -> {
                    for (String target : table.targets()) add(result, target);
                }
                default -> { }
            }
        }
        return result;
    }

    private void add(List<Ir.BasicBlock> into, String label) {
        Ir.BasicBlock block = byLabel.get(label);
        if (block != null && !into.contains(block)) into.add(block);
    }

    public Set<Ir.VReg> liveOut(Ir.BasicBlock block) {
        return liveOut.getOrDefault(block, Set.of());
    }

    public Set<Ir.VReg> liveIn(Ir.BasicBlock block) {
        return liveIn.getOrDefault(block, Set.of());
    }

    /**
     * The set live immediately after each instruction of a block, in order.
     *
     * <p>Walked backwards from the block's live-out: at each step remove what the
     * instruction defines and add what it uses.
     */
    public List<Set<Ir.VReg>> liveAfterEach(Ir.BasicBlock block) {
        List<Ir.Instr> instructions = block.instructions();
        List<Set<Ir.VReg>> result = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) result.add(null);

        Set<Ir.VReg> live = new HashSet<>(liveOut(block));
        for (int i = instructions.size() - 1; i >= 0; i--) {
            result.set(i, new LinkedHashSet<>(live));
            Ir.Instr instruction = instructions.get(i);

            Ir.VReg definition = Uses.definitionOf(instruction);
            if (definition != null) live.remove(definition);
            for (Ir.Value value : Uses.of(instruction)) {
                if (value instanceof Ir.VReg vreg) live.add(vreg);
            }
        }
        return result;
    }
}
