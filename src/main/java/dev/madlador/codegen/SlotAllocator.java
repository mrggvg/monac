package dev.madlador.codegen;

import dev.madlador.ir.Ir;
import dev.madlador.opt.Uses;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assigns every virtual register a frame slot, reusing slots once a value is dead.
 *
 * <p>This is not register allocation — it is the memory equivalent, and it exists
 * because the machine has only four registers and none are available until M9. It
 * matters more than it sounds: without slot reuse, {@code (a+b)*(c+d)} would burn a
 * fresh slot for every temporary, and a frame can only address 64 of them.
 *
 * <p>Reuse costs nothing at run time, because the target's ALU instructions take a
 * memory source operand: {@code ADD A, [D-3]} is one instruction and one clock tick,
 * exactly like {@code ADD A, B}. That is also why replacing this with real register
 * allocation later is an optimisation rather than a correctness fix.
 */
public final class SlotAllocator {

    private final Map<Ir.VReg, Integer> slots = new HashMap<>();
    private int highWater;

    private final java.util.function.Predicate<Ir.VReg> needsSlot;

    /**
     * @param namedSlots slots already taken by declared variables; temporaries are
     *                   allocated above these
     * @param needsSlot  which virtual registers actually need memory. Once register
     *                   allocation runs, only the spilled ones do.
     */
    public SlotAllocator(Ir.Function function, int namedSlots,
                         java.util.function.Predicate<Ir.VReg> needsSlot) {
        this.needsSlot = needsSlot;
        allocate(function, namedSlots);
    }

    public SlotAllocator(Ir.Function function, int namedSlots) {
        this(function, namedSlots, vreg -> true);
    }

    private void allocate(Ir.Function function, int namedSlots) {
        highWater = namedSlots;

        // Instructions are numbered across the whole function so a value that
        // outlives its block still gets a slot that nothing else reuses.
        List<Ir.Instr> all = new ArrayList<>();
        Map<String, Integer> blockStart = new HashMap<>();
        Map<String, Integer> blockEnd = new HashMap<>();

        for (Ir.BasicBlock block : function.blocks()) {
            blockStart.put(block.label(), all.size());
            all.addAll(block.instructions());
            blockEnd.put(block.label(), all.size() - 1);
        }

        Map<Ir.VReg, Integer> firstDef = new HashMap<>();
        Map<Ir.VReg, Integer> lastUse = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            Ir.VReg defined = definition(all.get(i));
            if (defined != null) {
                firstDef.putIfAbsent(defined, i);
                // A write occupies the slot as surely as a read does. A later write that
                // nothing reads — a dead store the optimizer left in a loop — still
                // lands in the slot, so the slot is not free until after it. Counting
                // only reads handed the slot to a loop counter, and the dead store
                // reset the counter every time round: an -O1 program that never halted,
                // which the program fuzzer found.
                lastUse.merge(defined, i, Math::max);
            }
            for (Ir.Value value : uses(all.get(i))) {
                if (value instanceof Ir.VReg vreg) lastUse.merge(vreg, i, Math::max);
            }
        }

        extendAcrossLoops(all, blockStart, blockEnd, firstDef, lastUse);

        Deque<Integer> free = new ArrayDeque<>();
        Map<Integer, List<Ir.VReg>> expiringAt = new HashMap<>();

        for (int i = 0; i < all.size(); i++) {
            Ir.VReg defined = definition(all.get(i));
            if (defined != null && !needsSlot.test(defined)) defined = null;
            if (defined != null && !slots.containsKey(defined)) {
                int slot = free.isEmpty() ? highWater++ : free.pop();
                slots.put(defined, slot);

                Integer death = lastUse.get(defined);
                // A value that is never read is dead immediately after its definition.
                int expiry = (death == null) ? i : death;
                expiringAt.computeIfAbsent(expiry, k -> new ArrayList<>()).add(defined);
            }

            for (Ir.VReg dead : expiringAt.getOrDefault(i, List.of())) {
                Integer slot = slots.get(dead);
                if (slot != null && !free.contains(slot)) free.push(slot);
            }
        }
    }

    /**
     * Widens the live range of anything carried around a loop.
     *
     * <p>A linear scan of last uses is only sound while control flow moves forward.
     * A back edge — a branch to a block that starts earlier than the branch itself —
     * makes an instruction inside the loop reachable again <em>after</em> what
     * linearly looks like the value's final use, so its slot would already have been
     * handed to something else and the loop-carried value would be corrupted on the
     * second iteration.
     *
     * <p>The fix is the standard one: for every back edge, any value defined before
     * the loop and used inside it stays live to the end of the loop body. Missing
     * this is the classic linear-scan bug, and it fails silently rather than loudly.
     */
    private static void extendAcrossLoops(List<Ir.Instr> all,
                                          Map<String, Integer> blockStart,
                                          Map<String, Integer> blockEnd,
                                          Map<Ir.VReg, Integer> firstDef,
                                          Map<Ir.VReg, Integer> lastUse) {
        // A back edge is a branch whose target begins at or before the branch.
        record Loop(int start, int end) {
        }
        List<Loop> loops = new ArrayList<>();

        for (int i = 0; i < all.size(); i++) {
            for (String target : branchTargets(all.get(i))) {
                Integer start = blockStart.get(target);
                if (start != null && start <= i) {
                    loops.add(new Loop(start, i));
                }
            }
        }
        if (loops.isEmpty()) return;

        // Iterate: extending one range can bring a value into an enclosing loop.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Loop loop : loops) {
                Set<Ir.VReg> live = new HashSet<>();
                for (Map.Entry<Ir.VReg, Integer> entry : lastUse.entrySet()) {
                    Ir.VReg vreg = entry.getKey();
                    int use = entry.getValue();
                    int def = firstDef.getOrDefault(vreg, Integer.MAX_VALUE);
                    // Used inside the loop, and its range does not already cover it.
                    boolean usedInLoop = use >= loop.start() && use <= loop.end();
                    boolean definedBeforeEnd = def <= loop.end();
                    if (usedInLoop && definedBeforeEnd && use < loop.end()) {
                        live.add(vreg);
                    }
                }
                for (Ir.VReg vreg : live) {
                    lastUse.put(vreg, loop.end());
                    changed = true;
                }
            }
        }
    }

    private static List<String> branchTargets(Ir.Instr instruction) {
        return switch (instruction) {
            case Ir.Br br -> List.of(br.target());
            case Ir.Cbr cbr -> List.of(cbr.ifTrue(), cbr.ifFalse());
            case Ir.TableBr table -> table.targets();
            default -> List.of();
        };
    }

    // Definitions and uses are described once, in Uses, so that adding an IR
    // instruction means teaching one place about it rather than several.

    private static Ir.VReg definition(Ir.Instr instruction) {
        return Uses.definitionOf(instruction);
    }

    private static List<Ir.Value> uses(Ir.Instr instruction) {
        return Uses.of(instruction);
    }

    public int slotOf(Ir.VReg vreg) {
        Integer slot = slots.get(vreg);
        if (slot == null) throw new IllegalStateException("no slot allocated for " + vreg);
        return slot;
    }

    public boolean hasSlot(Ir.VReg vreg) {
        return slots.containsKey(vreg);
    }

    /** Total slots the frame needs, named variables included. */
    public int totalSlots() {
        return highWater;
    }
}
