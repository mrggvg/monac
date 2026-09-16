package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns local variables into virtual registers so the allocator can see them.
 *
 * <p>Without this, only compiler temporaries are candidates for a machine register
 * and every declared variable stays in the frame — which is exactly backwards, since
 * a loop counter is the value most worth keeping in one.
 *
 * <p>Each promotable slot gets one dedicated virtual register, and its loads and
 * stores become copies. That single register is live wherever the variable is,
 * including around a loop, which is precisely the property the interference graph
 * needs; no SSA or phi nodes are required to express it.
 *
 * <p>A slot is promotable only when it holds a full-width scalar whose address is
 * never taken. Arrays are excluded because they are addressed by offset, byte-width
 * variables because storing to one must truncate to eight bits and a register copy
 * would not, and address-taken variables because a pointer could reach the memory
 * this pass would stop writing to.
 */
public final class PromoteLocals implements Pass {

    @Override
    public String name() {
        return "promote-locals";
    }

    @Override
    public boolean run(Ir.Function function) {
        Set<Integer> promotable = function.promotableSlots();
        if (promotable.isEmpty()) return false;

        Map<Integer, Ir.VReg> registerForSlot = new HashMap<>();
        int nextVreg = function.vregCount();
        boolean changed = false;

        for (Ir.BasicBlock block : function.blocks()) {
            List<Ir.Instr> instructions = block.instructions();
            for (int i = 0; i < instructions.size(); i++) {
                Ir.Instr instruction = instructions.get(i);

                if (instruction instanceof Ir.Load load
                        && load.addr() instanceof Ir.Addr.Local local && local.offset() == 0
                        && promotable.contains(local.slot())) {
                    Ir.VReg home = registerForSlot.get(local.slot());
                    if (home == null) {
                        home = new Ir.VReg(nextVreg++);
                        registerForSlot.put(local.slot(), home);
                    }
                    instructions.set(i, new Ir.Copy(load.dst(), home));
                    changed = true;
                    continue;
                }

                if (instruction instanceof Ir.Store store
                        && store.addr() instanceof Ir.Addr.Local local && local.offset() == 0
                        && promotable.contains(local.slot())) {
                    Ir.VReg home = registerForSlot.get(local.slot());
                    if (home == null) {
                        home = new Ir.VReg(nextVreg++);
                        registerForSlot.put(local.slot(), home);
                    }
                    instructions.set(i, new Ir.Copy(home, store.src()));
                    changed = true;
                }
            }
        }

        function.setVregCount(nextVreg);
        if (changed) shrinkFrame(function);
        return changed;
    }

    /**
     * Drops frame slots that promotion made unused.
     *
     * <p>Worth more than the stack space: a function whose frame becomes empty no
     * longer needs the {@code SUB SP} that reserves it, and that is what lets frame
     * pointer elimination remove its prologue and epilogue entirely.
     *
     * <p>Only the top of the frame is reclaimed. Slot indices of anything still in
     * memory have to stay where they are, so a gap below a surviving slot is left
     * alone rather than renumbered.
     */
    private static void shrinkFrame(Ir.Function function) {
        int highestUsed = -1;
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                Ir.Addr addr = addressOf(instruction);
                if (addr instanceof Ir.Addr.Local local) {
                    highestUsed = Math.max(highestUsed, local.slot());
                }
            }
        }
        function.setSlotCount(Math.min(function.slotCount(), highestUsed + 1));
    }

    private static Ir.Addr addressOf(Ir.Instr instruction) {
        return switch (instruction) {
            case Ir.Load l -> l.addr();
            case Ir.Store s -> s.addr();
            case Ir.AddrOf a -> a.addr();
            default -> null;
        };
    }
}
