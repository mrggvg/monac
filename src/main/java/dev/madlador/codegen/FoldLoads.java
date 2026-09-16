package dev.madlador.codegen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds a loaded value back into the instruction that consumes it.
 *
 * <p>The machine's ALU instructions take a memory source operand at the same one
 * clock tick as a register one, so
 *
 * <pre>
 *   MOV B, [SP+5]        becomes      ADD A, [SP+5]
 *   ADD A, B
 * </pre>
 *
 * and the load disappears once {@link DeadMoves} notices nothing reads {@code B} any
 * more. Data movement is around 40% of everything emitted, so this is aimed at the
 * largest category rather than at register pressure — which measurement showed is
 * not where the cost is.
 *
 * <p>What makes this safe is knowing when the remembered operand stops being
 * accurate. Three things invalidate it, and all three are easy to overlook:
 * <ul>
 *   <li>writing the register itself;</li>
 *   <li>writing the base register an indirect operand is relative to — otherwise
 *       {@code [B+2]} silently starts naming a different address;</li>
 *   <li>anything that could change memory, which is any store or call. A folded load
 *       happens <em>later</em> than the original one, so a write in between would
 *       change what it reads.</li>
 * </ul>
 *
 * <p>The machine also allows at most one memory operand per instruction, so a fold
 * is only attempted where the other operand is a register.
 */
public final class FoldLoads {

    private FoldLoads() {
    }

    public static List<Asm.Line> optimize(List<Asm.Line> lines) {
        List<Asm.Line> out = new ArrayList<>(lines.size());
        Map<Asm.Reg, Asm.Operand> origin = new HashMap<>();

        for (Asm.Line line : lines) {
            if (!(line instanceof Asm.Insn insn)) {
                // A label may be reached from anywhere, so nothing is known there.
                if (line instanceof Asm.Label) origin.clear();
                out.add(line);
                continue;
            }

            Asm.Insn rewritten = fold(insn, origin);
            update(rewritten, origin);
            out.add(rewritten);
        }
        return out;
    }

    /** Substitutes a remembered operand into the source position, if it is legal. */
    private static Asm.Insn fold(Asm.Insn insn, Map<Asm.Reg, Asm.Operand> origin) {
        List<Asm.Operand> operands = insn.operands();
        if (operands.size() != 2) return insn;
        if (!canFoldInto(insn.mnemonic())) return insn;

        // The destination must stay a register: memory is never an ALU destination.
        if (!(operands.get(0) instanceof Asm.Register)) return insn;
        if (!(operands.get(1) instanceof Asm.Register source)) return insn;

        Asm.Operand remembered = origin.get(source.reg());
        if (remembered == null) return insn;

        // One memory operand per instruction; the destination is a register here, so
        // the only question is whether the replacement is itself memory.
        List<Asm.Operand> replaced = List.of(operands.get(0), remembered);
        return new Asm.Insn(insn.mnemonic(), replaced, insn.comment());
    }

    /** Records what a plain load leaves in a register, and forgets what it must. */
    private static void update(Asm.Insn insn, Map<Asm.Reg, Asm.Operand> origin) {
        String mnemonic = insn.mnemonic();
        List<Asm.Operand> operands = insn.operands();

        // A call or a store can change memory under a remembered operand.
        if (mnemonic.equals("CALL") || mnemonic.equals("RET") || mnemonic.equals("IRET")) {
            origin.clear();
            return;
        }
        // PUSH and POP both move SP and touch memory, so every remembered operand
        // is suspect: one relative to SP now names a different address, and one in
        // memory may have just been written over.
        if (mnemonic.startsWith("PUSH") || mnemonic.startsWith("POP")) {
            origin.clear();
            return;
        }
        if (writesMemory(insn)) {
            origin.clear();
            return;
        }
        // MUL, DIV and IN write A without naming it.
        if (mnemonic.equals("MUL") || mnemonic.equals("DIV") || mnemonic.equals("IN")) {
            forget(origin, Asm.Reg.A);
        }

        if (operands.isEmpty() || !writesFirstOperand(mnemonic)) return;
        if (!(operands.get(0) instanceof Asm.Register destination)) return;

        Asm.Reg written = wholeRegister(destination.reg());
        forget(origin, written);

        // Only a full-width move from a foldable place is worth remembering — and
        // never a load through the register being written. `MOV B, [B]` leaves B
        // holding the value rather than the pointer, so `[B]` afterwards names a
        // completely different address.
        if (mnemonic.equals("MOV") && operands.size() == 2 && isFoldable(operands.get(1))
                && !isRelativeTo(operands.get(1), written)) {
            origin.put(written, operands.get(1));
        }
    }

    /** Drops what {@code written} held, and anything addressed relative to it. */
    private static void forget(Map<Asm.Reg, Asm.Operand> origin, Asm.Reg written) {
        origin.remove(written);
        origin.entrySet().removeIf(entry ->
                entry.getValue() instanceof Asm.Indirect indirect
                        && indirect.base() == written);
    }

    /** Whether an operand is addressed relative to {@code register}. */
    private static boolean isRelativeTo(Asm.Operand operand, Asm.Reg register) {
        return operand instanceof Asm.Indirect indirect && indirect.base() == register;
    }

    private static boolean isFoldable(Asm.Operand operand) {
        return operand instanceof Asm.Indirect
                || operand instanceof Asm.Absolute
                || operand instanceof Asm.Imm;
    }

    /** Whether the instruction stores to memory, which invalidates every operand. */
    private static boolean writesMemory(Asm.Insn insn) {
        if (!writesFirstOperand(insn.mnemonic()) || insn.operands().isEmpty()) return false;
        Asm.Operand destination = insn.operands().get(0);
        return destination instanceof Asm.Indirect || destination instanceof Asm.Absolute;
    }

    /** Instructions whose source operand may be memory or an immediate. */
    private static boolean canFoldInto(String mnemonic) {
        return switch (mnemonic) {
            case "MOV", "ADD", "SUB", "AND", "OR", "XOR", "SHL", "SHR", "CMP" -> true;
            default -> false;
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

    private static Asm.Reg wholeRegister(Asm.Reg register) {
        return switch (register) {
            case AH, AL -> Asm.Reg.A;
            case BH, BL -> Asm.Reg.B;
            case CH, CL -> Asm.Reg.C;
            case DH, DL -> Asm.Reg.D;
            default -> register;
        };
    }
}
