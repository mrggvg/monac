package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes computations whose result is never read.
 *
 * <p>Only instructions that are pure are candidates: a store, a call, a branch and a
 * return all have effects beyond their destination register, so they stay whatever
 * their result is used for. A call is kept even when its value is discarded, since
 * it may write through a pointer or to the display.
 */
public final class DeadCode implements Pass {

    @Override
    public String name() {
        return "dead-code";
    }

    @Override
    public boolean run(Ir.Function function) {
        Set<Ir.VReg> live = collectUses(function);
        boolean changed = false;

        for (Ir.BasicBlock block : function.blocks()) {
            List<Ir.Instr> kept = new ArrayList<>(block.instructions().size());
            for (Ir.Instr instruction : block.instructions()) {
                Ir.VReg defined = pureDefinition(instruction);
                if (defined != null && !live.contains(defined)) {
                    changed = true;
                    continue;
                }
                kept.add(instruction);
            }
            if (kept.size() != block.instructions().size()) {
                block.instructions().clear();
                block.instructions().addAll(kept);
            }
        }
        return changed;
    }

    private static Set<Ir.VReg> collectUses(Ir.Function function) {
        Set<Ir.VReg> live = new HashSet<>();
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                for (Ir.Value value : Uses.of(instruction)) {
                    if (value instanceof Ir.VReg vreg) live.add(vreg);
                }
            }
        }
        return live;
    }

    /** The register an instruction defines, but only if removing it would be safe. */
    private static Ir.VReg pureDefinition(Ir.Instr instruction) {
        return switch (instruction) {
            case Ir.Const c -> c.dst();
            case Ir.Copy c -> c.dst();
            case Ir.Bin b -> b.dst();
            case Ir.Un u -> u.dst();
            case Ir.Cmp c -> c.dst();
            case Ir.AddrOf a -> a.dst();
            // A load could fault on a bad pointer, but so could the code that made
            // the pointer; dropping an unread load is the usual and safe choice here.
            case Ir.Load l -> l.dst();
            // Everything else has effects worth keeping.
            default -> null;
        };
    }
}
