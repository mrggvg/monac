package dev.madlador.ir;

import dev.madlador.codegen.Runtime;

/**
 * Which allocator this program gets, decided by looking at the whole of it.
 *
 * <p>A general allocator has to search, because it cannot know what it will be asked
 * for. That is what costs: the bitmap allocator spends about 124 instructions handing
 * out six bytes, two thirds of it touching every two-byte unit twice — once to check
 * it is free and once to mark it. C answers this with size classes, and every fast
 * allocator is some arrangement of them: glibc has 62 bins and a per-thread cache,
 * musl groups same-size slots, tcmalloc and jemalloc keep per-thread free lists. None
 * of them can choose at compile time, because {@code malloc} is a library compiled
 * once for callers it will never see.
 *
 * <p>We are not a library, and two things are visible here that are not visible to C:
 *
 * <ul>
 *   <li><b>Whether the program ever frees.</b> {@code tree.mona} allocates a node per
 *       insert and never releases one. {@link DeadSymbols} has already reduced
 *       {@code runtimeHelpers()} to what is reachable, so the absence of
 *       {@link Runtime#FREE} means it, rather than meaning "not yet".
 *   <li><b>What sizes it asks for.</b> {@code __alloc(sizeof(struct Node))} folds to
 *       {@code MOV B, 6} before this runs. A program with one object type — which is
 *       what a machine with 4 KB is realistically used for — has one size.
 * </ul>
 *
 * <p>So: no frees means a bump pointer, one size means fixed cells, and anything else
 * means the bitmap. The bitmap is not being replaced. It is the general case, and the
 * backstop the other two are checked against.
 *
 * <p>Must run after the optimizer. Constant folding is what makes the size an
 * {@link Ir.Imm}, and reachability is what makes "never frees" a fact rather than a
 * guess — {@code Compiler} runs {@code PassManager} before the emitter asks.
 */
public final class HeapStrategy {

    /** Which of the three, and why it matters is on each constant. */
    public enum Kind {
        /**
         * A bump pointer, for a program that never frees.
         *
         * <p>Region allocation, which C programmers do by hand precisely because
         * their compiler cannot do it for them. There is no metadata at all, so the
         * whole region is data rather than eight ninths of it, and the allocator's
         * own code — several hundred bytes on a machine with 4,096 — comes back as
         * heap.
         */
        BUMP,

        /**
         * Fixed cells of one size, with one bit each saying whether the cell is live.
         *
         * <p>The general allocator with the unit made equal to the object: a bit per
         * cell instead of a bit per two bytes, so there is one bit to find and one to
         * set rather than a run of them. musl's allocator groups same-size slots
         * behind a bitmap for the same reason; the difference is that it chooses the
         * size class as the program runs and this chooses it as the program compiles,
         * so there is only ever one group.
         *
         * <p>A free list threaded through the free cells would be faster still, but it
         * is what makes a double free silent in C, and a bit per cell keeps that
         * detectable for a fraction of what the general allocator's bitmap costs.
         */
        SLAB,

        /** Two bitmaps over two-byte units: the general case, and the fallback. */
        BITMAP
    }

    private final Kind kind;
    private final int cellSize;

    private HeapStrategy(Kind kind, int cellSize) {
        this.kind = kind;
        this.cellSize = cellSize;
    }

    public Kind kind() {
        return kind;
    }

    /** The cell stride, for {@link Kind#SLAB} only. */
    public int cellSize() {
        if (kind != Kind.SLAB) throw new IllegalStateException("only a slab has cells");
        return cellSize;
    }

    /** Works out which allocator suits {@code module}. */
    public static HeapStrategy of(Ir.Module module) {
        if (!module.runtimeHelpers().contains(Runtime.ALLOC)
                && !module.runtimeHelpers().contains(Runtime.FREE)) {
            return new HeapStrategy(Kind.BITMAP, 0);
        }
        if (!module.runtimeHelpers().contains(Runtime.FREE)) {
            return new HeapStrategy(Kind.BUMP, 0);
        }
        int size = soleAllocationSize(module);
        return size > 0 ? new HeapStrategy(Kind.SLAB, size) : new HeapStrategy(Kind.BITMAP, 0);
    }

    /**
     * The strategy named on the command line, as far as the program allows.
     *
     * <p>Two of the three have a precondition — a bump allocator has no {@code free}
     * to emit, and a slab has no cell size unless every allocation agrees on one — so
     * a program that does not meet it gets the general allocator instead. Asking is
     * not an error, because the point of asking is to run the same program through
     * every allocator that can run it; {@link #kind()} says which one it really got.
     */
    public static HeapStrategy forced(Kind kind, Ir.Module module) {
        return switch (kind) {
            case BUMP -> module.runtimeHelpers().contains(Runtime.FREE)
                    ? new HeapStrategy(Kind.BITMAP, 0)
                    : new HeapStrategy(Kind.BUMP, 0);
            case SLAB -> {
                int size = soleAllocationSize(module);
                yield size > 0
                        ? new HeapStrategy(Kind.SLAB, size)
                        : new HeapStrategy(Kind.BITMAP, 0);
            }
            case BITMAP -> new HeapStrategy(Kind.BITMAP, 0);
        };
    }

    /**
     * The one size every allocation asks for, or 0 if they differ or any is dynamic.
     *
     * <p>Rounded up to even and to at least two bytes, so that every cell boundary is
     * even and an odd pointer stays an honest sign of one that never came from here.
     *
     * <p>{@code __alloc} is a builtin lowered to a direct call by
     * {@link Lowering#lowerBuiltin}, never reached through a function pointer, so
     * walking the calls really does see every allocation in the program.
     */
    private static int soleAllocationSize(Ir.Module module) {
        int found = 0;
        for (Ir.Function function : module.functions()) {
            for (Ir.BasicBlock block : function.blocks()) {
                for (Ir.Instr instruction : block.instructions()) {
                    if (!(instruction instanceof Ir.Call call)
                            || !Runtime.ALLOC.equals(call.target())) {
                        continue;
                    }
                    if (call.args().size() != 1
                            || !(call.args().get(0) instanceof Ir.Imm imm)) {
                        return 0;               // asked for a size only it knows
                    }
                    int size = Math.max(2, (imm.value() + 1) & 0xFFFE);
                    if (found != 0 && found != size) {
                        return 0;               // more than one kind of object
                    }
                    found = size;
                }
            }
        }
        return found;
    }

    @Override
    public String toString() {
        return kind == Kind.SLAB ? "SLAB(" + cellSize + ")" : kind.toString();
    }
}
