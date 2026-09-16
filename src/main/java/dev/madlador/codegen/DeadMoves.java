package dev.madlador.codegen;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Removes register writes nothing reads.
 *
 * <p>Worth having because data movement is the single largest category of emitted
 * instruction — around 40% of the corpus — and the two-address shape of the machine
 * means a copy precedes most operations. Several of those copies turn out to be to a
 * register that is never read again: a result written to its allocated home when the
 * only consumer already took it from the accumulator, for instance.
 *
 * <p>This is liveness over the emitted assembly rather than over the IR, because
 * only here are the real registers known. Blocks are delimited by labels, successors
 * come from the branches, and the analysis iterates to a fixpoint so a loop's back
 * edge keeps its values alive.
 *
 * <p>Deliberately conservative in three places: {@code D} and {@code SP} are always
 * live, anything reached by a branch whose target is unknown keeps everything alive,
 * and a call is modelled by what this convention actually guarantees rather than by
 * assuming the worst. Being wrong here deletes a write that something needed, which is
 * the worst kind of bug to chase.
 */
public final class DeadMoves {

    private DeadMoves() {
    }

    /** Registers that are always live: the frame pointer and the stack pointer. */
    private static final Set<Asm.Reg> ALWAYS_LIVE = EnumSet.of(Asm.Reg.D, Asm.Reg.SP);

    /** Everything an unknown successor might read. */
    private static final Set<Asm.Reg> ALL =
            EnumSet.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C, Asm.Reg.D, Asm.Reg.SP);

    /**
     * What a call destroys. Not D or SP: the callee restores the frame pointer and
     * balances the stack, and the caller reclaims its arguments itself.
     */
    private static final Set<Asm.Reg> CLOBBERED_BY_CALL =
            EnumSet.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C);

    public static List<Asm.Line> optimize(List<Asm.Line> lines) {
        List<Block> blocks = split(lines);
        if (blocks.isEmpty()) return lines;

        computeLiveOut(blocks);

        List<Asm.Line> out = new ArrayList<>(lines.size());
        for (Block block : blocks) {
            out.addAll(block.withoutDeadMoves());
        }
        return out;
    }

    /* ---------------- blocks ---------------- */

    private static final class Block {
        final String label;
        final List<Asm.Line> lines = new ArrayList<>();
        final Set<Asm.Reg> liveOut = EnumSet.noneOf(Asm.Reg.class);
        final List<String> successors = new ArrayList<>();
        boolean fallsThrough = true;
        boolean unknownExit;

        Block(String label) {
            this.label = label;
        }

        /** The registers live at the top, derived by walking backwards. */
        Set<Asm.Reg> liveIn() {
            Set<Asm.Reg> live = EnumSet.copyOf(liveOut);
            live.addAll(ALWAYS_LIVE);
            for (int i = lines.size() - 1; i >= 0; i--) {
                step(lines.get(i), live);
            }
            return live;
        }

        List<Asm.Line> withoutDeadMoves() {
            Set<Asm.Reg> live = EnumSet.copyOf(liveOut);
            live.addAll(ALWAYS_LIVE);

            List<Asm.Line> kept = new ArrayList<>(lines.size());
            for (int i = lines.size() - 1; i >= 0; i--) {
                Asm.Line line = lines.get(i);
                Asm.Reg dead = deadDestination(line, live);
                if (dead != null) continue;
                step(line, live);
                kept.add(line);
            }
            java.util.Collections.reverse(kept);
            return kept;
        }

        /** The register a line writes and nothing reads, or null. */
        private Asm.Reg deadDestination(Asm.Line line, Set<Asm.Reg> live) {
            if (!(line instanceof Asm.Insn insn)) return null;
            // Only plain register-to-something moves are safe to drop; anything else
            // may set flags or touch memory.
            if (!insn.mnemonic().equals("MOV") || insn.operands().size() != 2) return null;
            if (!(insn.operands().get(0) instanceof Asm.Register destination)) return null;

            Asm.Reg reg = destination.reg();
            if (ALWAYS_LIVE.contains(reg)) return null;
            return live.contains(reg) ? null : reg;
        }
    }

    /** Applies one instruction backwards: kill what it defines, revive what it reads. */
    private static void step(Asm.Line line, Set<Asm.Reg> live) {
        if (!(line instanceof Asm.Insn insn)) return;
        String mnemonic = insn.mnemonic();
        List<Asm.Operand> operands = insn.operands();

        // A call under this convention clobbers A, B and C and reads its first
        // argument in B. Nothing survives it: the allocator forbids every register to
        // any value live across a call ("no register survives a call" in
        // InterferenceGraph), so a value the caller still wants afterwards is already
        // in a frame slot. That makes a write to A, B or C before a call dead unless
        // the callee reads it.
        //
        // The hand-written helpers in Runtime follow the same convention -- argument 0
        // in B, further arguments at [D+5] upwards, result in A -- and their header
        // says so explicitly, so they are covered by the same reasoning.
        //
        // B is put back unconditionally because arity is not visible here: a call to a
        // no-argument function does not read B, and keeping it live costs one missed
        // removal rather than a wrong one.
        if (mnemonic.equals("CALL")) {
            live.removeAll(CLOBBERED_BY_CALL);
            live.add(Asm.Reg.B);
            // An indirect call reads the register holding its target. Today that is
            // only the interrupt trampoline's CALL [B], which the line above already
            // covers, but the base is read whatever it is.
            if (!operands.isEmpty() && operands.get(0) instanceof Asm.Indirect indirect) {
                live.add(indirect.base());
            }
            return;
        }
        // MUL and DIV read and write A implicitly.
        if (mnemonic.equals("MUL") || mnemonic.equals("DIV") || mnemonic.equals("IN")) {
            live.add(Asm.Reg.A);
        }
        if (mnemonic.equals("OUT")) live.add(Asm.Reg.A);

        boolean writesFirst = writesFirstOperand(mnemonic) && !operands.isEmpty();
        if (writesFirst && operands.get(0) instanceof Asm.Register destination) {
            // A plain move overwrites; everything else reads its destination too.
            if (mnemonic.equals("MOV") || mnemonic.equals("MOVB")) {
                live.remove(destination.reg());
            } else {
                live.add(destination.reg());
            }
        }

        for (int i = 0; i < operands.size(); i++) {
            if (i == 0 && writesFirst) {
                addIndirectBase(operands.get(i), live);
                continue;
            }
            addUses(operands.get(i), live);
        }
    }

    private static void addUses(Asm.Operand operand, Set<Asm.Reg> live) {
        if (operand instanceof Asm.Register register) {
            live.add(wholeRegister(register.reg()));
        }
        addIndirectBase(operand, live);
    }

    private static void addIndirectBase(Asm.Operand operand, Set<Asm.Reg> live) {
        if (operand instanceof Asm.Indirect indirect) live.add(indirect.base());
    }

    /** A write to a byte half is a write to part of its 16-bit register. */
    private static Asm.Reg wholeRegister(Asm.Reg register) {
        return switch (register) {
            case AH, AL -> Asm.Reg.A;
            case BH, BL -> Asm.Reg.B;
            case CH, CL -> Asm.Reg.C;
            case DH, DL -> Asm.Reg.D;
            default -> register;
        };
    }

    private static boolean writesFirstOperand(String mnemonic) {
        return switch (mnemonic) {
            case "MOV", "MOVB", "ADD", "ADDB", "SUB", "SUBB", "AND", "ANDB",
                 "OR", "ORB", "XOR", "XORB", "SHL", "SHLB", "SHR", "SHRB",
                 "INC", "INCB", "DEC", "DECB", "NOT", "NOTB", "POP", "POPB" -> true;
            default -> false;
        };
    }

    /* ---------------- control flow ---------------- */

    private static List<Block> split(List<Asm.Line> lines) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block(null);
        blocks.add(current);

        for (Asm.Line line : lines) {
            if (line instanceof Asm.Label label) {
                current = new Block(label.name());
                blocks.add(current);
                current.lines.add(line);
                continue;
            }
            current.lines.add(line);

            if (line instanceof Asm.Insn insn) {
                String mnemonic = insn.mnemonic();
                if (mnemonic.equals("JMP")) {
                    current.fallsThrough = false;
                    recordTarget(current, insn);
                } else if (mnemonic.startsWith("J")) {
                    recordTarget(current, insn);
                } else if (mnemonic.equals("RET") || mnemonic.equals("IRET")
                        || mnemonic.equals("HLT")) {
                    current.fallsThrough = false;
                    // A return leaves the result in A.
                    current.liveOut.add(Asm.Reg.A);
                }
            }
        }
        return blocks;
    }

    private static void recordTarget(Block block, Asm.Insn insn) {
        if (insn.operands().size() == 1
                && insn.operands().get(0) instanceof Asm.LabelRef ref) {
            block.successors.add(ref.label());
        } else {
            // A computed jump: the target is not a label we can follow.
            block.unknownExit = true;
        }
    }

    private static void computeLiveOut(List<Block> blocks) {
        Map<String, Block> byLabel = new HashMap<>();
        for (Block block : blocks) {
            if (block.label != null) byLabel.put(block.label, block);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = blocks.size() - 1; i >= 0; i--) {
                Block block = blocks.get(i);
                Set<Asm.Reg> out = EnumSet.copyOf(block.liveOut);

                if (block.unknownExit) out.addAll(ALL);
                for (String target : block.successors) {
                    Block successor = byLabel.get(target);
                    if (successor == null) out.addAll(ALL);
                    else out.addAll(successor.liveIn());
                }
                if (block.fallsThrough && i + 1 < blocks.size()) {
                    out.addAll(blocks.get(i + 1).liveIn());
                }

                if (!out.equals(block.liveOut)) {
                    block.liveOut.clear();
                    block.liveOut.addAll(out);
                    changed = true;
                }
            }
        }
    }
}
