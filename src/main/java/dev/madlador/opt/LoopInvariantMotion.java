package dev.madlador.opt;

import dev.madlador.ir.Ir;
import dev.madlador.regalloc.Liveness;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Moves computations that give the same answer on every iteration out of the loop.
 *
 * <p>Loops are found the classical way: an edge whose target dominates its source is a
 * back edge, and the loop it closes is its target plus everything that reaches the
 * edge without passing through the target. An instruction inside is hoisted into a
 * preheader — a block made to be the one way into the loop from outside — when:
 *
 * <ul>
 *   <li>it is a {@code Bin} or {@code Un}: the only instructions whose hoisting saves
 *       an instruction per iteration. A constant, a copy or an address costs as little
 *       inside the loop as outside, and moving it would only tie up a register for the
 *       whole loop, of which there are two;</li>
 *   <li>it cannot fault. Division and remainder are left where they are: hoisted out
 *       of a branch that guards against zero, one would run when the loop never would
 *       have divided;</li>
 *   <li>its operands are the same on every iteration — constants, values defined only
 *       outside the loop, or values this pass has already decided to hoist;</li>
 *   <li>its result is defined nowhere else. The IR is not SSA, and a value assigned in
 *       two places cannot be computed once for both.</li>
 * </ul>
 *
 * <p>Hoisting runs the instruction even when the path through the loop that contained
 * it would not have, which is harmless for a pure computation that cannot fault. It
 * lengthens the result's life across the whole loop, which is the real cost with two
 * registers; the measurements in optimization.md say where it pays.
 */
public final class LoopInvariantMotion implements Pass {

    @Override
    public String name() {
        return "loop-invariant-motion";
    }

    @Override
    public boolean run(Ir.Function function) {
        Cfg cfg = new Cfg(function);
        Map<Ir.VReg, Integer> definitions = definitionCounts(function);

        boolean changed = false;
        for (Map.Entry<Ir.BasicBlock, Set<Ir.BasicBlock>> loop : cfg.loops().entrySet()) {
            changed |= hoist(function, cfg, loop.getKey(), loop.getValue(), definitions);
            // A preheader changes the graph, so one loop per round; the pass manager
            // runs again, and an outer loop then sees the inner one's preheader.
            if (changed) return true;
        }
        return false;
    }

    private static Map<Ir.VReg, Integer> definitionCounts(Ir.Function function) {
        Map<Ir.VReg, Integer> counts = new HashMap<>();
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                Ir.VReg defined = Uses.definitionOf(instruction);
                if (defined != null) counts.merge(defined, 1, Integer::sum);
            }
        }
        return counts;
    }

    private boolean hoist(Ir.Function function, Cfg cfg, Ir.BasicBlock header,
                          Set<Ir.BasicBlock> body, Map<Ir.VReg, Integer> definitions) {
        if (header == function.entry()) return false;

        // What is defined inside the loop, and so not invariant unless hoisted.
        Set<Ir.VReg> definedInside = new HashSet<>();
        for (Ir.BasicBlock block : body) {
            for (Ir.Instr instruction : block.instructions()) {
                Ir.VReg defined = Uses.definitionOf(instruction);
                if (defined != null) definedInside.add(defined);
            }
        }

        // To a fixpoint, in program order, so a chain of invariants comes out in order.
        List<Ir.Instr> hoisted = new ArrayList<>();
        Set<Ir.VReg> invariant = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Ir.BasicBlock block : function.blocks()) {
                if (!body.contains(block)) continue;
                for (Ir.Instr instruction : block.instructions()) {
                    if (hoisted.contains(instruction) || !movable(instruction)) continue;
                    Ir.VReg defined = Uses.definitionOf(instruction);
                    if (definitions.getOrDefault(defined, 0) != 1) continue;
                    boolean operandsFixed = true;
                    for (Ir.Value operand : Uses.of(instruction)) {
                        if (operand instanceof Ir.VReg vreg && definedInside.contains(vreg)
                                && !invariant.contains(vreg)) {
                            operandsFixed = false;
                            break;
                        }
                    }
                    if (!operandsFixed) continue;
                    hoisted.add(instruction);
                    invariant.add(defined);
                    grew = true;
                }
            }
        }
        if (hoisted.isEmpty()) return false;
        if (!worthTheRegisters(function, header, body, hoisted.size())) return false;

        Ir.BasicBlock preheader = preheader(function, cfg, header, body);
        if (preheader == null) return false;
        for (Ir.BasicBlock block : body) block.instructions().removeAll(hoisted);
        List<Ir.Instr> instructions = preheader.instructions();
        instructions.addAll(instructions.size() - 1, hoisted);   // before its branch
        return true;
    }

    /**
     * Whether hoisting {@code hoisting} values out of this loop is worth the registers.
     *
     * <p>A loop that calls anything keeps every value in memory — no register survives
     * a call here — and a value in memory costs nothing to read, since an ALU takes a
     * memory operand at no extra cost. There a hoisted value is pure gain. Otherwise it
     * holds a register for the whole loop, and with two of them that can push something
     * the loop reads every time round out to memory. Measured: hoisting {@code r * 10}
     * and {@code r << 3} out of an inner loop that fitted in two registers sent its
     * counter to memory and made it slower, not faster. So without a call, only while
     * the loop still fits. Letting a loop already over two hoist as well was measured
     * too, and changed nothing in the corpus, so it is not here.
     */
    private static boolean worthTheRegisters(Ir.Function function, Ir.BasicBlock header,
                                             Set<Ir.BasicBlock> body, int hoisting) {
        for (Ir.BasicBlock block : body) {
            for (Ir.Instr instruction : block.instructions()) {
                if (instruction instanceof Ir.Call) return true;
            }
        }
        int live = new Liveness(function).liveIn(header).size();
        return live + hoisting <= 2;
    }

    private static boolean movable(Ir.Instr instruction) {
        return switch (instruction) {
            case Ir.Bin b -> b.op() != Ir.BinOp.DIV && b.op() != Ir.BinOp.MOD;
            case Ir.Un ignored -> true;
            default -> false;
        };
    }

    /**
     * The one way into the loop from outside: a new block between every outside
     * predecessor and the header. Null when an outside edge is a jump table's, whose
     * targets live in module data this pass does not rewrite.
     */
    private static Ir.BasicBlock preheader(Ir.Function function, Cfg cfg, Ir.BasicBlock header,
                                           Set<Ir.BasicBlock> body) {
        List<Ir.BasicBlock> outside = new ArrayList<>();
        for (Ir.BasicBlock predecessor : cfg.predecessors(header)) {
            if (body.contains(predecessor)) continue;
            if (terminator(predecessor) instanceof Ir.TableBr) return null;
            outside.add(predecessor);
        }
        if (outside.isEmpty()) return null;

        Ir.BasicBlock preheader = new Ir.BasicBlock(header.label() + "_pre");
        preheader.add(new Ir.Br(header.label()));
        for (Ir.BasicBlock predecessor : outside) {
            List<Ir.Instr> instructions = predecessor.instructions();
            Ir.Instr last = terminator(predecessor);
            if (last == null) continue;   // falls through: the preheader is placed before the header
            instructions.set(instructions.size() - 1, retarget(last, header.label(), preheader.label()));
        }
        function.blocks().add(function.blocks().indexOf(header), preheader);
        return preheader;
    }

    private static Ir.Instr terminator(Ir.BasicBlock block) {
        if (!block.isTerminated()) return null;
        return block.instructions().get(block.instructions().size() - 1);
    }

    private static Ir.Instr retarget(Ir.Instr branch, String from, String to) {
        return switch (branch) {
            case Ir.Br b -> b.target().equals(from) ? new Ir.Br(to) : b;
            case Ir.Cbr c -> new Ir.Cbr(c.cond(), c.lhs(), c.rhs(),
                    c.ifTrue().equals(from) ? to : c.ifTrue(),
                    c.ifFalse().equals(from) ? to : c.ifFalse());
            default -> branch;
        };
    }

    /** Successors, predecessors, dominators and natural loops, over reachable blocks. */
    private static final class Cfg {
        private final List<Ir.BasicBlock> blocks = new ArrayList<>();
        private final Map<Ir.BasicBlock, List<Ir.BasicBlock>> successors = new HashMap<>();
        private final Map<Ir.BasicBlock, List<Ir.BasicBlock>> predecessors = new HashMap<>();
        private final Map<Ir.BasicBlock, Set<Ir.BasicBlock>> dominators = new HashMap<>();

        Cfg(Ir.Function function) {
            Map<String, Ir.BasicBlock> byLabel = new HashMap<>();
            for (Ir.BasicBlock block : function.blocks()) byLabel.put(block.label(), block);

            List<Ir.BasicBlock> all = function.blocks();
            for (int i = 0; i < all.size(); i++) {
                Ir.BasicBlock block = all.get(i);
                List<Ir.BasicBlock> out = new ArrayList<>();
                Ir.Instr last = terminator(block);
                if (last == null) {
                    if (i + 1 < all.size()) out.add(all.get(i + 1));   // falls through
                } else {
                    for (String label : targets(last)) {
                        Ir.BasicBlock target = byLabel.get(label);
                        if (target != null) out.add(target);
                    }
                }
                successors.put(block, out);
            }

            // Only what the entry reaches: an unreachable block has no dominators.
            Deque<Ir.BasicBlock> pending = new ArrayDeque<>(List.of(function.entry()));
            Set<Ir.BasicBlock> reached = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                Ir.BasicBlock block = pending.pop();
                if (!reached.add(block)) continue;
                pending.addAll(successors.get(block));
            }
            for (Ir.BasicBlock block : all) if (reached.contains(block)) blocks.add(block);
            for (Ir.BasicBlock block : blocks) predecessors.put(block, new ArrayList<>());
            for (Ir.BasicBlock block : blocks) {
                for (Ir.BasicBlock successor : successors.get(block)) {
                    predecessors.get(successor).add(block);
                }
            }

            Set<Ir.BasicBlock> everything = new LinkedHashSet<>(blocks);
            for (Ir.BasicBlock block : blocks) {
                dominators.put(block, block == function.entry()
                        ? new LinkedHashSet<>(List.of(block)) : new LinkedHashSet<>(everything));
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (Ir.BasicBlock block : blocks) {
                    if (block == function.entry()) continue;
                    Set<Ir.BasicBlock> meet = null;
                    for (Ir.BasicBlock predecessor : predecessors.get(block)) {
                        if (meet == null) meet = new LinkedHashSet<>(dominators.get(predecessor));
                        else meet.retainAll(dominators.get(predecessor));
                    }
                    if (meet == null) meet = new LinkedHashSet<>();
                    meet.add(block);
                    if (!meet.equals(dominators.get(block))) {
                        dominators.put(block, meet);
                        changed = true;
                    }
                }
            }
        }

        List<Ir.BasicBlock> predecessors(Ir.BasicBlock block) {
            return predecessors.getOrDefault(block, List.of());
        }

        /** Each loop header, with every block in its loop. Loops sharing a header merge. */
        Map<Ir.BasicBlock, Set<Ir.BasicBlock>> loops() {
            Map<Ir.BasicBlock, Set<Ir.BasicBlock>> loops = new LinkedHashMap<>();
            for (Ir.BasicBlock source : blocks) {
                for (Ir.BasicBlock header : successors.get(source)) {
                    if (!dominators.get(source).contains(header)) continue;   // not a back edge
                    Set<Ir.BasicBlock> body = loops.computeIfAbsent(header,
                            h -> new LinkedHashSet<>(List.of(h)));
                    Deque<Ir.BasicBlock> pending = new ArrayDeque<>(List.of(source));
                    while (!pending.isEmpty()) {
                        Ir.BasicBlock block = pending.pop();
                        if (!body.add(block)) continue;
                        pending.addAll(predecessors(block));
                    }
                }
            }
            return loops;
        }

        private static List<String> targets(Ir.Instr branch) {
            return switch (branch) {
                case Ir.Br b -> List.of(b.target());
                case Ir.Cbr c -> List.of(c.ifTrue(), c.ifFalse());
                case Ir.TableBr t -> t.targets();
                default -> List.of();
            };
        }
    }
}
