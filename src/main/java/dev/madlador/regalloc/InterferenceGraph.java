package dev.madlador.regalloc;

import dev.madlador.codegen.Asm;
import dev.madlador.ir.Ir;
import dev.madlador.opt.Uses;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which virtual registers cannot share a machine register.
 *
 * <p>Two values interfere when both are live at the same point. The graph is built
 * by walking each block backwards over the live sets and adding an edge between what
 * an instruction defines and everything else live just after it.
 *
 * <p>Two machine-specific constraints are recorded alongside the edges:
 *
 * <ul>
 *   <li><b>{@code MUL} and {@code DIV} read and write {@code A} implicitly.</b> Any
 *       value live across one of those instructions therefore cannot live in
 *       {@code A}, which is expressed by adding {@code A} to that value's set of
 *       forbidden registers. This is the pre-colouring the whole allocation has to
 *       respect.</li>
 *   <li><b>A call clobbers everything.</b> There are no callee-saved registers in
 *       this convention, so a value live across a call cannot live in any register
 *       and is forced to memory.</li>
 * </ul>
 *
 * <p>Copy edges are recorded separately so that {@code MOV dst, src} pairs can be
 * coalesced onto one register and the move deleted — worth a lot here, since the IR
 * is three-address while the machine is two-address, so the selector inserts a copy
 * before every binary operation.
 */
public final class InterferenceGraph {

    private final Map<Ir.VReg, Set<Ir.VReg>> edges = new LinkedHashMap<>();
    private final Map<Ir.VReg, Set<Asm.Reg>> forbidden = new HashMap<>();
    private final Set<Ir.VReg> mustSpill = new HashSet<>();
    private final Map<Ir.VReg, Integer> useCount = new HashMap<>();
    private final List<Copy> copies = new ArrayList<>();
    private final Map<Ir.VReg, Asm.Reg> preferred = new HashMap<>();

    /** A {@code dst = src} pair worth putting in the same register. */
    public record Copy(Ir.VReg destination, Ir.VReg source) {
    }

    public InterferenceGraph(Ir.Function function, Liveness liveness) {
        build(function, liveness);
    }

    private void build(Ir.Function function, Liveness liveness) {
        for (Ir.BasicBlock block : function.blocks()) {
            List<Ir.Instr> instructions = block.instructions();
            List<Set<Ir.VReg>> liveAfter = liveness.liveAfterEach(block);

            for (int i = 0; i < instructions.size(); i++) {
                Ir.Instr instruction = instructions.get(i);
                Set<Ir.VReg> live = liveAfter.get(i);

                for (Ir.Value value : Uses.of(instruction)) {
                    if (value instanceof Ir.VReg vreg) {
                        useCount.merge(vreg, 1, Integer::sum);
                        node(vreg);
                    }
                }

                // An argument that arrived in B costs a move if it is coloured
                // anything else, so say so. A preference, not a constraint: if B is
                // taken the value goes elsewhere and the move is emitted.
                if (instruction instanceof Ir.ArgIn arrived) {
                    preferred.put(arrived.dst(), Asm.Reg.B);
                }

                Ir.VReg defined = Uses.definitionOf(instruction);
                if (defined != null) {
                    node(defined);
                    for (Ir.VReg other : live) {
                        if (!other.equals(defined)) addEdge(defined, other);
                    }
                }

                applyConstraints(instruction, live, defined);
                recordCopy(instruction);
            }
        }
    }

    private void applyConstraints(Ir.Instr instruction, Set<Ir.VReg> live, Ir.VReg defined) {
        // MOD is expanded into DIV and MUL by the instruction selector, so it
        // clobbers A exactly as they do. Leaving it out of this list meant the
        // expansion could destroy the register holding its own left operand.
        if (instruction instanceof Ir.Bin bin
                && (bin.op() == Ir.BinOp.MUL || bin.op() == Ir.BinOp.DIV
                    || bin.op() == Ir.BinOp.MOD)) {
            // A is both the implicit source and the destination of MUL and DIV, so
            // nothing else may be sitting in it across this instruction.
            for (Ir.VReg vreg : live) {
                if (!vreg.equals(defined)) forbid(vreg, Asm.Reg.A);
            }

            // The right operand is read after A has been loaded with the left, so it
            // cannot be in A either.
            if (bin.rhs() instanceof Ir.VReg rhs) forbid(rhs, Asm.Reg.A);

            // MOD is different from MUL and DIV in one way that matters: its
            // expansion reads the LEFT operand a second time, after DIV and MUL have
            // already overwritten A. So unlike a plain multiply — where putting the
            // left operand in A is exactly what you want — a remainder must have it
            // somewhere that survives.
            if (bin.op() == Ir.BinOp.MOD && bin.lhs() instanceof Ir.VReg lhs) {
                forbid(lhs, Asm.Reg.A);
            }
        }

        if (instruction instanceof Ir.Call) {
            // No register survives a call under this convention.
            for (Ir.VReg vreg : live) {
                if (!vreg.equals(defined)) mustSpill.add(vreg);
            }
        }
    }

    private void recordCopy(Ir.Instr instruction) {
        if (instruction instanceof Ir.Copy copy && copy.src() instanceof Ir.VReg source) {
            copies.add(new Copy(copy.dst(), source));
        }
    }

    private void node(Ir.VReg vreg) {
        edges.computeIfAbsent(vreg, k -> new LinkedHashSet<>());
    }

    private void addEdge(Ir.VReg a, Ir.VReg b) {
        node(a);
        node(b);
        edges.get(a).add(b);
        edges.get(b).add(a);
    }

    private void forbid(Ir.VReg vreg, Asm.Reg register) {
        node(vreg);
        forbidden.computeIfAbsent(vreg, k -> new LinkedHashSet<>()).add(register);
    }

    public Set<Ir.VReg> nodes() {
        return edges.keySet();
    }

    public Set<Ir.VReg> neighboursOf(Ir.VReg vreg) {
        return edges.getOrDefault(vreg, Set.of());
    }

    public Set<Asm.Reg> forbiddenFor(Ir.VReg vreg) {
        return forbidden.getOrDefault(vreg, Set.of());
    }

    public boolean mustSpill(Ir.VReg vreg) {
        return mustSpill.contains(vreg);
    }

    /** The register this value would rather have, or null. */
    public Asm.Reg preferredFor(Ir.VReg vreg) {
        return preferred.get(vreg);
    }

    public int useCountOf(Ir.VReg vreg) {
        return useCount.getOrDefault(vreg, 0);
    }

    public List<Copy> copies() {
        return copies;
    }
}
