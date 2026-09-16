package dev.madlador.codegen;

import java.util.List;

/**
 * The assembly representation, and the single place that knows how to spell it.
 *
 * <p>Two of the target assembler's rules are easy to violate and produce a file that
 * simply will not assemble, so both are enforced here rather than at the many places
 * that build instructions:
 * <ul>
 *   <li>An indirect operand may not contain spaces. The line regex is
 *       {@code \[(\w+((\+|-)\d+)?)\]}, so {@code [D-2]} assembles and
 *       {@code [D - 2]} is a syntax error.</li>
 *   <li>A negative immediate cannot be written at all: {@code MOV A, -5} is
 *       rejected. Immediates are therefore always rendered as unsigned 16-bit, so
 *       -5 comes out as 65531.</li>
 * </ul>
 */
public final class Asm {

    private Asm() {
    }

    /** Registers nameable in assembly. IP and SR exist but cannot be written. */
    public enum Reg {
        A, B, C, D, SP,
        AH, AL, BH, BL, CH, CL, DH, DL;

        public boolean isByteWide() {
            return ordinal() >= AH.ordinal();
        }

        /** The 8-bit low half of a 16-bit register. */
        public Reg low() {
            return switch (this) {
                case A -> AL;
                case B -> BL;
                case C -> CL;
                case D -> DL;
                default -> throw new IllegalStateException(this + " has no low half");
            };
        }
    }

    public sealed interface Operand permits Imm, Register, Indirect, Absolute, LabelRef {
    }

    /** An immediate. Always emitted unsigned; the assembler rejects a minus sign. */
    public record Imm(int value) implements Operand {
        public Imm {
            value &= 0xFFFF;
        }
    }

    public record Register(Reg reg) implements Operand {
    }

    /** {@code [base+offset]}. The displacement must fit in a signed byte. */
    public record Indirect(Reg base, int offset) implements Operand {
        public Indirect {
            if (offset < -128 || offset > 127) {
                throw new IllegalArgumentException(
                        "indirect displacement " + offset + " is outside -128..127");
            }
        }
    }

    /** {@code [label]} or {@code [0x1234]}: a direct memory address. */
    public record Absolute(String label) implements Operand {
    }

    /** A bare label, as used by JMP, CALL and DW. */
    public record LabelRef(String label) implements Operand {
    }

    /* ---------------- lines ---------------- */

    public sealed interface Line permits Insn, Label, Comment, Blank, Directive, Raw {
    }

    /**
     * A pre-formatted line, used for the hand-written runtime helpers. Everything
     * the compiler generates goes through the structured forms, so the spelling
     * rules above still apply to all of it.
     */
    public record Raw(String text) implements Line {
    }

    public record Insn(String mnemonic, List<Operand> operands, String comment) implements Line {
        public Insn(String mnemonic, Operand... operands) {
            this(mnemonic, List.of(operands), null);
        }

        public Insn withComment(String text) {
            return new Insn(mnemonic, operands, text);
        }
    }

    public record Label(String name) implements Line {
    }

    public record Comment(String text) implements Line {
    }

    public record Blank() implements Line {
    }

    /** {@code DB}, {@code DW} or {@code ORG} — the only three the assembler has. */
    public record Directive(String name, List<Operand> operands, String comment) implements Line {
        public Directive(String name, Operand... operands) {
            this(name, List.of(operands), null);
        }
    }

    /* ---------------- rendering ---------------- */

    public static String render(Operand operand) {
        return switch (operand) {
            case Imm i -> Integer.toString(i.value());
            case Register r -> r.reg().name();
            // No spaces inside the brackets: the assembler's line regex forbids them.
            case Indirect ind -> {
                if (ind.offset() == 0) yield "[" + ind.base().name() + "]";
                yield "[" + ind.base().name() + (ind.offset() > 0 ? "+" : "-")
                        + Math.abs(ind.offset()) + "]";
            }
            case Absolute a -> "[" + a.label() + "]";
            case LabelRef l -> l.label();
        };
    }

    public static String render(Line line) {
        return switch (line) {
            case Blank ignored -> "";
            case Comment c -> "; " + c.text();
            case Label l -> l.name() + ":";
            case Insn insn -> renderOperation(insn.mnemonic(), insn.operands(), insn.comment());
            case Directive d -> renderOperation(d.name(), d.operands(), d.comment());
            case Raw r -> r.text();
        };
    }

    private static String renderOperation(String mnemonic, List<Operand> operands, String comment) {
        StringBuilder sb = new StringBuilder("        ");
        sb.append(mnemonic);
        if (!operands.isEmpty()) {
            sb.append(' ');
            // Pad short mnemonics so operand columns line up.
            for (int i = mnemonic.length(); i < 5; i++) sb.append(' ');
            for (int i = 0; i < operands.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(render(operands.get(i)));
            }
        }
        if (comment != null) {
            while (sb.length() < 44) sb.append(' ');
            sb.append("; ").append(comment);
        }
        return sb.toString();
    }

    public static String render(List<Line> lines) {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            sb.append(render(line)).append('\n');
        }
        return sb.toString();
    }
}
