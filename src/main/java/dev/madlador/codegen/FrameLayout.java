package dev.madlador.codegen;

/**
 * Where things live in a stack frame.
 *
 * <p>This is the piece the target machine turns on its head. Its {@code PUSH} writes
 * the word at {@code SP-1} and then lowers {@code SP} by two, so the stack pointer
 * addresses the next <em>free</em> byte, one below the top-of-stack word:
 *
 * <pre>
 *   pushWord(v) { storeWord(SP - 1, v); SP -= 2; }
 *   popWord()   { v = loadWord(SP + 1); SP += 2; }
 * </pre>
 *
 * <p>The consequence is that every frame offset is <b>odd</b>. With the prologue
 *
 * <pre>
 *   f:  PUSH D          ; save the caller's frame pointer
 *       MOV D, SP       ; D = frame base
 *       SUB SP, size    ; reserve locals
 * </pre>
 *
 * the frame reads:
 *
 * <pre>
 *   [D+1]              saved caller D
 *   [D+3]              return address (pushed by CALL)
 *   [D+5], [D+7], ...  incoming arguments, first argument nearest
 *   [D-1], [D-3], ...  local slots
 * </pre>
 *
 * <p>Note that no shuffling of the return address is needed. {@code MOV SP, D}
 * followed by {@code POP D} leaves it exactly where {@code RET} expects it, which is
 * why the epilogue is three instructions rather than the pop-and-push dance the
 * original design sketch used.
 */
public final class FrameLayout {

    /** Bytes between the frame base and the first argument. */
    private static final int FIRST_ARG_OFFSET = 5;

    /** The indirect displacement field is a signed byte. */
    public static final int MIN_OFFSET = -128;
    public static final int MAX_OFFSET = 127;

    /** The most local word slots a frame can address: [D-1] down to [D-127]. */
    public static final int MAX_SLOTS = (MAX_OFFSET - 1) / 2 + 1;   // 64

    /** The most arguments a function can take: one in {@code B}, then [D+5]..[D+127]. */
    public static final int MAX_ARGS = (MAX_OFFSET - FIRST_ARG_OFFSET) / 2 + 2;   // 63

    private final int slotCount;

    public FrameLayout(int slotCount) {
        this.slotCount = slotCount;
    }

    /** Bytes to reserve with {@code SUB SP, n}. */
    public int frameSize() {
        return slotCount * 2;
    }

    public int slotCount() {
        return slotCount;
    }

    /** Displacement of local slot {@code i}: -1, -3, -5, ... */
    public static int slotOffset(int slot) {
        return -(2 * slot + 1);
    }

    /**
     * Displacement of argument {@code i}: +5, +7, +9, ...
     *
     * <p>Argument 0 has no displacement. It travels in {@code B} and never reaches
     * the stack, so the remaining arguments each sit one word lower than the naive
     * numbering suggests — argument 1 is where argument 0 used to be.
     */
    public static int argOffset(int index) {
        if (index == 0) {
            throw new IllegalArgumentException("argument 0 is passed in a register");
        }
        return FIRST_ARG_OFFSET + 2 * (index - 1);
    }

    public static boolean slotFits(int slot) {
        return slotOffset(slot) >= MIN_OFFSET;
    }

    public static boolean argFits(int index) {
        return index == 0 || argOffset(index) <= MAX_OFFSET;
    }

    /** {@code [D-1]} for local slot 0, and so on. */
    public static Asm.Indirect local(int slot) {
        return local(slot, 0);
    }

    /**
     * A byte {@code offset} into local slot {@code slot}.
     *
     * <p>Used for struct members and for nothing else. Offsets grow upwards from the
     * slot's own address, which is why a multi-slot variable is anchored at the slot
     * with the <em>lowest</em> address — see the note in the analyzer.
     */
    public static Asm.Indirect local(int slot, int offset) {
        return new Asm.Indirect(Asm.Reg.D, slotOffset(slot) + offset);
    }

    /** {@code [D+5]} for the first argument, and so on. */
    public static Asm.Indirect argument(int index) {
        return new Asm.Indirect(Asm.Reg.D, argOffset(index));
    }
}
