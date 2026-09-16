package dev.madlador.ir;

import java.util.ArrayList;
import java.util.List;

/**
 * The intermediate representation: three-address code over unlimited virtual
 * registers, organised into basic blocks.
 *
 * <p>Deliberately <em>not</em> SSA. SSA pays for itself with sparse global
 * optimisation we are not doing, and charges for it at phi-destruction time — which
 * on a machine with three allocatable registers means parallel-copy sequencing with
 * no scratch register to spare. Instead each virtual register is assigned once per
 * basic block, which is free and buys most of the local-optimisation benefit.
 *
 * <p>The one design decision worth calling out is that both {@link Cmp} and
 * {@link Cbr} exist. Lowering {@code if (a < b)} straight to a {@code Cbr} keeps it
 * to a compare and a branch; going through a materialised 0/1 would cost about
 * seven instructions instead of two. Keeping {@code Cbr} atomic is also a
 * structural guarantee that no later pass can schedule something between the
 * {@code CMP} and its {@code Jcc} and clobber the flags.
 */
public final class Ir {

    private Ir() {
    }

    /* ---------------- values ---------------- */

    public sealed interface Value permits VReg, Imm, GlobalRef {
    }

    /** A virtual register. Unlimited in supply; mapped to storage during codegen. */
    public record VReg(int id) implements Value {
        @Override
        public String toString() {
            return "%" + id;
        }
    }

    /** A 16-bit immediate. Always stored unsigned, because the assembler rejects
     *  a negative literal outright; -5 is emitted as 65531. */
    public record Imm(int value) implements Value {
        public Imm {
            value &= 0xFFFF;
        }

        @Override
        public String toString() {
            return Integer.toString(value);
        }
    }

    /** The address of a global or a string literal. */
    public record GlobalRef(String label) implements Value {
        @Override
        public String toString() {
            return "@" + label;
        }
    }

    /* ---------------- addresses ---------------- */

    /** Where a load or store goes. Resolved to real offsets by frame lowering. */
    public sealed interface Addr permits Addr.Local, Addr.Arg, Addr.Global, Addr.Mem {

        /**
         * A frame slot holding a local variable or a compiler temporary, plus a byte
         * displacement into it.
         *
         * <p>The displacement is what makes a struct member as cheap as a plain
         * local. A member sits some bytes into its variable, and {@code [D+k]}
         * already takes a displacement, so {@code s.y} is one instruction rather than
         * an address computed into a register first. It is zero for everything else.
         */
        record Local(int slot, int offset) implements Addr {

            public Local(int slot) {
                this(slot, 0);
            }

            @Override
            public String toString() {
                return "local[" + slot + "]" + (offset == 0 ? "" : "+" + offset);
            }
        }

        /** An incoming argument, by position. */
        record Arg(int index) implements Addr {
            @Override
            public String toString() {
                return "arg[" + index + "]";
            }
        }

        /** A global, by mangled label. */
        record Global(String label) implements Addr {
            @Override
            public String toString() {
                return "@" + label;
            }
        }

        /** Indirect through a register, with a signed displacement. */
        record Mem(VReg base, int offset) implements Addr {
            @Override
            public String toString() {
                return "[" + base + (offset >= 0 ? "+" : "") + offset + "]";
            }
        }
    }

    /** Access width. Byte accesses use MOVB and the 8-bit register halves. */
    public enum Width {
        BYTE, WORD
    }

    /* ---------------- operations ---------------- */

    public enum BinOp {
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"),
        AND("&"), OR("|"), XOR("^"), SHL("<<"), SHR(">>");

        private final String symbol;

        BinOp(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        /** Operands of these may never be swapped. */
        public boolean isCommutative() {
            return this == ADD || this == MUL || this == AND || this == OR || this == XOR;
        }
    }

    public enum UnOp {
        NEG, NOT, LNOT
    }

    /** Condition codes, named for what they mean rather than for the flag bits. */
    public enum Cond {
        EQ, NE, ULT, ULE, UGT, UGE, SLT, SLE, SGT, SGE;

        public Cond inverted() {
            return switch (this) {
                case EQ -> NE;
                case NE -> EQ;
                case ULT -> UGE;
                case UGE -> ULT;
                case UGT -> ULE;
                case ULE -> UGT;
                case SLT -> SGE;
                case SGE -> SLT;
                case SGT -> SLE;
                case SLE -> SGT;
            };
        }

        public boolean isSigned() {
            return this == SLT || this == SLE || this == SGT || this == SGE;
        }
    }

    /* ---------------- instructions ---------------- */

    public sealed interface Instr
            permits Const, Copy, Bin, Un, Load, Store, Cmp, Cbr, Br, Call, Ret, AddrOf,
                    ArgIn, In, Out, Flag, TableBr {
    }

    /**
     * An incoming argument that arrived in a register rather than on the stack.
     *
     * <p>Emitted first in the entry block, once, for the argument the calling
     * convention passes in {@code B}. It defines a virtual register and uses nothing,
     * which is the whole trick: liveness and the interference graph then decide
     * whether the value can simply stay in {@code B} — free, for a leaf function that
     * reads it and returns — or has to be written to a frame slot because the
     * function makes a call of its own and {@code B} will not survive it. Neither
     * case is special-cased anywhere; the allocator was already doing this for every
     * other value.
     */
    public record ArgIn(VReg dst, int index) implements Instr {
        @Override
        public String toString() {
            return dst + " = argreg " + index;
        }
    }

    /**
     * A jump through a table of block addresses.
     *
     * <p>{@code index} is already biased to zero and range-checked by the time this
     * runs, so every value selects a real entry. {@code targets} is kept alongside
     * the label so the control-flow graph can see the successors.
     */
    public record TableBr(Value index, String tableLabel, List<String> targets)
            implements Instr {
        @Override
        public String toString() {
            return "tablebr " + index + " -> " + tableLabel + " " + targets;
        }
    }

    /** A table of block addresses, emitted as DW entries after the code. */
    public record JumpTable(String label, List<String> targets) {
    }

    /** Whether interrupts may be delivered: STI or CLI. */
    public enum FlagOp { ENABLE_INTERRUPTS, DISABLE_INTERRUPTS, HALT }

    /** Sets or clears the interrupt mask, or stops the machine. */
    public record Flag(FlagOp op) implements Instr {
        @Override
        public String toString() {
            return switch (op) {
                case ENABLE_INTERRUPTS -> "sti";
                case DISABLE_INTERRUPTS -> "cli";
                case HALT -> "halt";
            };
        }
    }

    public record Const(VReg dst, int value) implements Instr {
        @Override
        public String toString() {
            return dst + " = " + (value & 0xFFFF);
        }
    }

    public record Copy(VReg dst, Value src) implements Instr {
        @Override
        public String toString() {
            return dst + " = " + src;
        }
    }

    public record Bin(VReg dst, BinOp op, Value lhs, Value rhs) implements Instr {
        @Override
        public String toString() {
            return dst + " = " + lhs + " " + op.symbol() + " " + rhs;
        }
    }

    public record Un(VReg dst, UnOp op, Value src) implements Instr {
        @Override
        public String toString() {
            return dst + " = " + op + " " + src;
        }
    }

    /**
     * A read from memory.
     *
     * <p>{@code signed} matters only for a byte: the register halves are eight bits
     * and a value has to reach sixteen somehow, so the question is whether bit 7
     * becomes the top nine bits or whether they become zero. A {@code byte} zero-
     * extends and an {@code sbyte} sign-extends, and getting that wrong meant a
     * negative {@code sbyte} could be stored and never read back.
     *
     * <p>Stores need no equivalent, because both kinds truncate identically.
     */
    public record Load(VReg dst, Addr addr, Width width, boolean signed) implements Instr {

        /** A load that needs no sign extension, which is every word and every byte. */
        public Load(VReg dst, Addr addr, Width width) {
            this(dst, addr, width, false);
        }
        @Override
        public String toString() {
            return dst + " = load." + width.name().toLowerCase() + " " + addr;
        }
    }

    public record Store(Addr addr, Value src, Width width) implements Instr {
        @Override
        public String toString() {
            return "store." + width.name().toLowerCase() + " " + addr + ", " + src;
        }
    }

    /** Materialises a 0/1 from a comparison. Prefer {@link Cbr} where possible. */
    public record Cmp(VReg dst, Cond cond, Value lhs, Value rhs) implements Instr {
        @Override
        public String toString() {
            return dst + " = " + lhs + " " + cond + " " + rhs;
        }
    }

    /** Fused compare-and-branch; the terminator of a conditional block. */
    public record Cbr(Cond cond, Value lhs, Value rhs, String ifTrue, String ifFalse)
            implements Instr {
        @Override
        public String toString() {
            return "cbr " + lhs + " " + cond + " " + rhs + " ? " + ifTrue + " : " + ifFalse;
        }
    }

    public record Br(String target) implements Instr {
        @Override
        public String toString() {
            return "br " + target;
        }
    }

    /**
     * A call: to a label, or — when {@code target} is null — through {@code callee}, a
     * value holding a function's address.
     */
    public record Call(VReg dst, String target, Value callee, List<Value> args) implements Instr {

        public Call(VReg dst, String target, List<Value> args) {
            this(dst, target, null, args);
        }

        public boolean isIndirect() {
            return target == null;
        }

        @Override
        public String toString() {
            return (dst == null ? "" : dst + " = ") + "call "
                    + (target != null ? target : "*" + callee) + args;
        }
    }

    public record Ret(Value value) implements Instr {
        @Override
        public String toString() {
            return value == null ? "ret" : "ret " + value;
        }
    }

    /**
     * Reads an I/O port.
     *
     * <p>Ports are a separate index space from memory, and the peripherals live
     * there: the graphics card, the keypad, the timer and the random generator. Not
     * removable even when the result is unused, because reading a port can have an
     * effect — reading KBDDATA clears the keyboard status and lowers its interrupt.
     */
    public record In(VReg dst, Value port) implements Instr {
        @Override
        public String toString() {
            return dst + " = in " + port;
        }
    }

    /** Writes {@code value} to an I/O port. */
    public record Out(Value port, Value value) implements Instr {
        @Override
        public String toString() {
            return "out " + port + ", " + value;
        }
    }

    /** The address of a frame slot or global, for taking a pointer to it. */
    public record AddrOf(VReg dst, Addr addr) implements Instr {
        @Override
        public String toString() {
            return dst + " = &" + addr;
        }
    }

    /* ---------------- structure ---------------- */

    /** A straight-line run of instructions ending in a terminator. */
    public static final class BasicBlock {
        private final String label;
        private final List<Instr> instructions = new ArrayList<>();

        public BasicBlock(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public List<Instr> instructions() {
            return instructions;
        }

        public void add(Instr instruction) {
            instructions.add(instruction);
        }

        /** True once the block ends in a branch or return. */
        public boolean isTerminated() {
            if (instructions.isEmpty()) return false;
            Instr last = instructions.get(instructions.size() - 1);
            return last instanceof Br || last instanceof Cbr || last instanceof Ret
                    || last instanceof TableBr;
        }
    }

    /** One function: its blocks, its frame requirements, and its signature. */
    public static final class Function {
        private final String name;
        private final String label;
        private final int parameterCount;
        private final List<BasicBlock> blocks = new ArrayList<>();
        private int slotCount;
        private int vregCount;
        private final java.util.Set<Integer> promotableSlots = new java.util.LinkedHashSet<>();

        public Function(String name, String label, int parameterCount) {
            this.name = name;
            this.label = label;
            this.parameterCount = parameterCount;
        }

        public String name() {
            return name;
        }

        public String label() {
            return label;
        }

        public int parameterCount() {
            return parameterCount;
        }

        public List<BasicBlock> blocks() {
            return blocks;
        }

        public BasicBlock entry() {
            return blocks.get(0);
        }

        public int slotCount() {
            return slotCount;
        }

        public void setSlotCount(int slotCount) {
            this.slotCount = slotCount;
        }

        public int vregCount() {
            return vregCount;
        }

        /**
         * Frame slots that hold a full-width scalar whose address is never taken,
         * and so may be replaced by a virtual register.
         */
        public java.util.Set<Integer> promotableSlots() {
            return promotableSlots;
        }

        public void setVregCount(int vregCount) {
            this.vregCount = vregCount;
        }
    }

    /**
     * A variable at file scope, emitted as DW and DB directives after the code.
     *
     * <p>{@code image} is its bytes as they start out, big-endian as the machine is.
     * {@code addresses} names the offsets that hold a label instead — a string's, a
     * global's, a function's — for the assembler to fill in.
     */
    public record Global(String label, int size, byte[] image,
                         java.util.Map<Integer, String> addresses) {

        /** A scalar, or a zeroed aggregate when {@code initialValue} is 0. */
        public Global(String label, int size, int initialValue) {
            this(label, size, scalarImage(size, initialValue), java.util.Map.of());
        }

        private static byte[] scalarImage(int size, int value) {
            byte[] image = new byte[Math.max(1, size)];
            if (size == 1) {
                image[0] = (byte) value;
            } else {
                image[0] = (byte) (value >> 8);
                image[1] = (byte) value;
            }
            return image;
        }
    }

    /** A NUL-terminated string literal, emitted after the code. */
    public record StringData(String label, String value) {
    }

    /** A whole program. */
    public static final class Module {
        private final List<Function> functions = new ArrayList<>();
        private final List<Global> globals = new ArrayList<>();
        private final List<StringData> strings = new ArrayList<>();
        private final List<JumpTable> jumpTables = new ArrayList<>();
        private final java.util.Set<String> runtimeHelpers = new java.util.LinkedHashSet<>();
        private boolean usesInterrupts;
        private boolean usesHeap;
        private String entryFunctionLabel;

        public List<Function> functions() {
            return functions;
        }

        public List<Global> globals() {
            return globals;
        }

        public List<StringData> strings() {
            return strings;
        }

        public List<JumpTable> jumpTables() {
            return jumpTables;
        }

        /** Whether the program installs an interrupt handler, which needs a vector. */
        public boolean usesInterrupts() {
            return usesInterrupts;
        }

        public void setUsesInterrupts(boolean value) {
            this.usesInterrupts = value;
        }

        /** Whether the program allocates, and so needs a heap set up at startup. */
        public boolean usesHeap() {
            return usesHeap;
        }

        public void setUsesHeap(boolean value) {
            this.usesHeap = value;
        }

        /** Runtime helper labels the program calls, so only those get emitted. */
        public java.util.Set<String> runtimeHelpers() {
            return runtimeHelpers;
        }

        public String entryFunctionLabel() {
            return entryFunctionLabel;
        }

        public void setEntryFunctionLabel(String label) {
            this.entryFunctionLabel = label;
        }
    }
}
