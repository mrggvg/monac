package dev.madlador.opt;

import dev.madlador.ir.Ir;
import dev.madlador.regalloc.Liveness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Replaces a register with what was copied into it.
 *
 * <p>Copies mostly come from lowering rather than from the source: taking the
 * address of an already-computed value, or materialising an operand so it can be a
 * base register. Forwarding them lets {@link DeadCode} delete the copy entirely.
 *
 * <p>Block-local for the same reason as {@link ConstFold}: without SSA there is no
 * safe way to know which definition reaches a block with several predecessors.
 *
 * <p>Which way a copy is forwarded matters to the register allocator. {@code x = t},
 * where {@code t} is a temporary defined once and {@code x} a variable assigned many
 * times, is how every {@code x = x - dy} lowers — and forwarding it the usual way
 * turns a later read of {@code x} into a read of {@code t}, which keeps {@code t}
 * alive past the copy, makes it interfere with {@code x}, and so stops the two ever
 * sharing a register: the subtraction goes through a scratch and costs three
 * instructions instead of one. So that one shape is forwarded the other way — later
 * reads of the temporary read the variable — and the temporary dies at the copy.
 *
 * <p>Only for a variable that is not live across a call, though. One that is lives in
 * memory whatever happens — no register survives a call here — and then reading it
 * costs a load that reading the temporary, still in a register, did not: measured,
 * it added an instruction a pixel to a line-drawing loop that calls {@code plot}.
 */
public final class CopyProp implements Pass {

    @Override
    public String name() {
        return "copy-prop";
    }

    @Override
    public boolean run(Ir.Function function) {
        Set<Ir.VReg> acrossCalls = liveAcrossCalls(function);
        Map<Ir.VReg, Integer> definitions = new HashMap<>();
        for (Ir.BasicBlock block : function.blocks()) {
            for (Ir.Instr instruction : block.instructions()) {
                Ir.VReg defined = Uses.definitionOf(instruction);
                if (defined != null) definitions.merge(defined, 1, Integer::sum);
            }
        }
        boolean changed = false;
        for (Ir.BasicBlock block : function.blocks()) {
            changed |= runOnBlock(block, definitions, acrossCalls);
        }
        return changed;
    }

    /** Values something reads after a call: those go to memory, whatever else happens. */
    private static Set<Ir.VReg> liveAcrossCalls(Ir.Function function) {
        Set<Ir.VReg> across = new HashSet<>();
        Liveness liveness = new Liveness(function);
        for (Ir.BasicBlock block : function.blocks()) {
            List<Set<Ir.VReg>> after = liveness.liveAfterEach(block);
            for (int i = 0; i < block.instructions().size(); i++) {
                if (block.instructions().get(i) instanceof Ir.Call call) {
                    for (Ir.VReg live : after.get(i)) {
                        if (!live.equals(call.dst())) across.add(live);
                    }
                }
            }
        }
        return across;
    }

    private boolean runOnBlock(Ir.BasicBlock block, Map<Ir.VReg, Integer> definitions,
                               Set<Ir.VReg> acrossCalls) {
        Map<Ir.VReg, Ir.Value> copies = new HashMap<>();
        List<Ir.Instr> instructions = block.instructions();
        boolean changed = false;

        for (int i = 0; i < instructions.size(); i++) {
            Ir.Instr rewritten = substitute(instructions.get(i), copies);
            if (rewritten != instructions.get(i)) {
                instructions.set(i, rewritten);
                changed = true;
            }

            Ir.Instr current = instructions.get(i);
            Ir.VReg defined = Uses.definitionOf(current);
            if (defined != null) {
                // Anything that was a copy OF this register is now stale.
                copies.entrySet().removeIf(e -> defined.equals(e.getValue()));
                copies.remove(defined);
            }
            if (current instanceof Ir.Copy copy) {
                if (copy.src() instanceof Ir.VReg temporary
                        && definitions.getOrDefault(temporary, 0) == 1
                        && definitions.getOrDefault(copy.dst(), 0) > 1
                        && !acrossCalls.contains(copy.dst())) {
                    // A temporary into a variable: the variable stands in for it.
                    copies.put(temporary, copy.dst());
                } else {
                    copies.put(copy.dst(), copy.src());
                }
            }
        }
        return changed;
    }

    private Ir.Instr substitute(Ir.Instr instruction, Map<Ir.VReg, Ir.Value> copies) {
        if (copies.isEmpty()) return instruction;

        return switch (instruction) {
            case Ir.Bin b -> {
                Ir.Value lhs = resolve(b.lhs(), copies);
                Ir.Value rhs = resolve(b.rhs(), copies);
                yield (lhs == b.lhs() && rhs == b.rhs())
                        ? b : new Ir.Bin(b.dst(), b.op(), lhs, rhs);
            }
            case Ir.Un u -> {
                Ir.Value src = resolve(u.src(), copies);
                yield src == u.src() ? u : new Ir.Un(u.dst(), u.op(), src);
            }
            case Ir.Copy c -> {
                Ir.Value src = resolve(c.src(), copies);
                yield src == c.src() ? c : new Ir.Copy(c.dst(), src);
            }
            case Ir.Store s -> {
                Ir.Value src = resolve(s.src(), copies);
                Ir.Addr addr = resolveAddress(s.addr(), copies);
                yield (src == s.src() && addr == s.addr())
                        ? s : new Ir.Store(addr, src, s.width());
            }
            case Ir.Load l -> {
                Ir.Addr addr = resolveAddress(l.addr(), copies);
                yield addr == l.addr() ? l : new Ir.Load(l.dst(), addr, l.width());
            }
            case Ir.Cmp c -> {
                Ir.Value lhs = resolve(c.lhs(), copies);
                Ir.Value rhs = resolve(c.rhs(), copies);
                yield (lhs == c.lhs() && rhs == c.rhs())
                        ? c : new Ir.Cmp(c.dst(), c.cond(), lhs, rhs);
            }
            case Ir.Cbr c -> {
                Ir.Value lhs = resolve(c.lhs(), copies);
                Ir.Value rhs = resolve(c.rhs(), copies);
                yield (lhs == c.lhs() && rhs == c.rhs())
                        ? c : new Ir.Cbr(c.cond(), lhs, rhs, c.ifTrue(), c.ifFalse());
            }
            case Ir.Ret r -> {
                if (r.value() == null) yield r;
                Ir.Value value = resolve(r.value(), copies);
                yield value == r.value() ? r : new Ir.Ret(value);
            }
            case Ir.Call c -> {
                List<Ir.Value> args = c.args().stream().map(a -> resolve(a, copies)).toList();
                Ir.Value callee = c.callee() == null ? null : resolve(c.callee(), copies);
                yield args.equals(c.args()) && callee == c.callee()
                        ? c : new Ir.Call(c.dst(), c.target(), callee, args);
            }
            default -> instruction;
        };
    }

    private static Ir.Value resolve(Ir.Value value, Map<Ir.VReg, Ir.Value> copies) {
        Ir.Value current = value;
        // Follow a chain of copies, with a bound so a cycle cannot hang the compiler.
        for (int i = 0; i < 16 && current instanceof Ir.VReg vreg; i++) {
            Ir.Value next = copies.get(vreg);
            if (next == null) break;
            current = next;
        }
        return current;
    }

    private static Ir.Addr resolveAddress(Ir.Addr addr, Map<Ir.VReg, Ir.Value> copies) {
        if (!(addr instanceof Ir.Addr.Mem mem)) return addr;
        // The base has to stay a register, so only forward register-to-register.
        Ir.Value resolved = resolve(mem.base(), copies);
        if (resolved instanceof Ir.VReg vreg && !vreg.equals(mem.base())) {
            return new Ir.Addr.Mem(vreg, mem.offset());
        }
        return addr;
    }
}
