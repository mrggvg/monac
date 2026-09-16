package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tidies the control-flow graph.
 *
 * <p>Lowering creates blocks freely — short-circuit operators and {@code for} loops
 * each open several — and many turn out to be a single unconditional jump, or to be
 * unreachable once a condition folded to a constant. Cleaning them up here is much
 * easier than recognising the same patterns in the emitted assembly.
 */
public final class BranchSimplify implements Pass {

    @Override
    public String name() {
        return "branch-simplify";
    }

    @Override
    public boolean run(Ir.Function function) {
        boolean changed = threadJumps(function);
        changed |= removeUnreachable(function);
        return changed;
    }

    /**
     * Redirects branches that target a block containing nothing but a jump, so
     * control goes straight to the eventual destination.
     */
    private boolean threadJumps(Ir.Function function) {
        Map<String, String> forwarding = new HashMap<>();
        for (Ir.BasicBlock block : function.blocks()) {
            if (block == function.entry()) continue;
            List<Ir.Instr> body = block.instructions();
            if (body.size() == 1 && body.get(0) instanceof Ir.Br br) {
                forwarding.put(block.label(), br.target());
            }
        }
        if (forwarding.isEmpty()) return false;

        boolean changed = false;
        for (Ir.BasicBlock block : function.blocks()) {
            List<Ir.Instr> body = block.instructions();
            for (int i = 0; i < body.size(); i++) {
                Ir.Instr rewritten = switch (body.get(i)) {
                    case Ir.Br br -> {
                        String target = follow(br.target(), forwarding);
                        yield target.equals(br.target()) ? br : new Ir.Br(target);
                    }
                    case Ir.Cbr cbr -> {
                        String t = follow(cbr.ifTrue(), forwarding);
                        String f = follow(cbr.ifFalse(), forwarding);
                        yield (t.equals(cbr.ifTrue()) && f.equals(cbr.ifFalse()))
                                ? cbr
                                : new Ir.Cbr(cbr.cond(), cbr.lhs(), cbr.rhs(), t, f);
                    }
                    default -> body.get(i);
                };
                if (rewritten != body.get(i)) {
                    body.set(i, rewritten);
                    changed = true;
                }
            }
        }
        return changed;
    }

    private static String follow(String label, Map<String, String> forwarding) {
        String current = label;
        // Bounded, so a loop of empty blocks cannot hang the compiler.
        for (int i = 0; i < 16; i++) {
            String next = forwarding.get(current);
            if (next == null || next.equals(current)) break;
            current = next;
        }
        return current;
    }

    /** Drops blocks nothing can branch to, which constant folding tends to create. */
    private boolean removeUnreachable(Ir.Function function) {
        Set<String> reachable = new HashSet<>();
        Map<String, Ir.BasicBlock> byLabel = new HashMap<>();
        for (Ir.BasicBlock block : function.blocks()) byLabel.put(block.label(), block);

        List<String> worklist = new ArrayList<>();
        worklist.add(function.entry().label());
        while (!worklist.isEmpty()) {
            String label = worklist.remove(worklist.size() - 1);
            if (!reachable.add(label)) continue;
            Ir.BasicBlock block = byLabel.get(label);
            if (block == null) continue;
            for (Ir.Instr instruction : block.instructions()) {
                switch (instruction) {
                    case Ir.Br br -> worklist.add(br.target());
                    case Ir.Cbr cbr -> {
                        worklist.add(cbr.ifTrue());
                        worklist.add(cbr.ifFalse());
                    }
                    case Ir.TableBr table -> worklist.addAll(table.targets());
                    default -> { }
                }
            }
        }

        return function.blocks().removeIf(
                block -> block != function.entry() && !reachable.contains(block.label()));
    }
}
