package dev.madlador.codegen;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.Span;
import dev.madlador.driver.Options;
import dev.madlador.ir.Ir;
import dev.madlador.ir.Lowering;
import dev.madlador.ir.HeapStrategy;
import dev.madlador.ir.StackDepth;
import dev.madlador.regalloc.GraphColoring;
import dev.madlador.regalloc.InterferenceGraph;
import dev.madlador.regalloc.Liveness;
import dev.madlador.sema.Mangler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assembles a whole module: the entry stub, then each function.
 *
 * <p>The entry sequence has to be laid out precisely. Execution starts at address 0
 * and the interrupt vector is fixed at {@code 0x0003}, so whatever sits at address 0
 * must be exactly three bytes. {@code JMP <label>} is exactly three bytes
 * (a one-byte opcode plus a two-byte address), which makes it the natural stub:
 *
 * <pre>
 *         JMP .start      ; bytes 0..2
 * .irq:   IRET            ; byte 3, the interrupt vector
 * .start: MOV SP, 0x0FFF
 *         CALL m_main
 *         HLT
 * </pre>
 *
 * <p>The handler is inert until a program executes {@code STI}, because interrupts
 * are masked at reset, but putting {@code IRET} there costs one byte and makes the
 * layout correct if handlers are ever added.
 */
public final class ModuleEmitter {

    /** Top of RAM. The first push then writes 0x0FFE..0x0FFF. */
    public static final int INITIAL_SP = 0x0FFF;

    /** RAM ends at 0x0FFF; above that is the memory-mapped display. */
    public static final int RAM_LIMIT = 0x1000;

    /** Where the interrupt vector sends control. */
    private static final String ISR_TRAMPOLINE = ".isr_dispatch";

    /**
     * The least stack a program is ever given, in bytes.
     *
     * <p>A floor rather than a budget. Where the call graph has no cycle in it the
     * requirement is computed exactly and is usually far smaller than this, and the
     * heap gets the difference. Where it does have a cycle there is no number to
     * compute, and this is what recursion is allowed to use before the run-time check
     * stops it — about forty frames.
     */
    private static final int STACK_RESERVE = 256;

    /** Slack over the computed requirement, for the two bytes a frame may yet need. */
    private static final int STACK_MARGIN = 16;

    private final DiagnosticReporter reporter;
    private final Mangler mangler;
    private int heapSize;
    private int imageBytes;
    private StackDepth stack;
    private boolean guardsStack;

    /**
     * Which allocator this program gets.
     *
     * <p>A property of the whole module rather than of any one call, so it is settled
     * once here and every heap decision below reads it: which bodies to emit, which
     * words to reserve, and what startup has to work out.
     */
    private HeapStrategy heap;

    /**
     * How much stack the program can need, or {@link StackDepth#UNBOUNDED}.
     *
     * <p>Reported by {@code --stats}, and the number the heap is sized around: a
     * program that provably needs 18 bytes of stack should not have 256 held back
     * from its heap on the strength of a guess.
     */
    public int stackBound() {
        return stack == null ? StackDepth.UNBOUNDED : stack.bound();
    }

    public ModuleEmitter(DiagnosticReporter reporter, Mangler mangler) {
        this.reporter = reporter;
        this.mangler = mangler;
    }

    /**
     * Bytes the last emitted image occupies once loaded, code and data together.
     *
     * <p>The same figure the size check uses, which is what makes it worth reporting:
     * the machine has 4096 bytes for the image and the stack together, so this is the
     * number that says whether a program fits, and instruction counts do not.
     */
    public int imageBytes() {
        return imageBytes;
    }

    public String emit(Ir.Module module, boolean withEntry) {
        return emit(module, withEntry, 1, 0);
    }

    public String emit(Ir.Module module, boolean withEntry, int optLevel) {
        return emit(module, withEntry, optLevel, 0);
    }

    /**
     * @param heapSize bytes of heap, 0 for none, or {@link Options#HEAP_AUTO} for
     *                 everything between the end of the image and the stack reserve
     */
    public String emit(Ir.Module module, boolean withEntry, int optLevel, int heapSize) {
        return emit(module, withEntry, optLevel, heapSize, null);
    }

    /**
     * @param forcedHeap the allocator to emit, or null to choose one from the program
     */
    public String emit(Ir.Module module, boolean withEntry, int optLevel, int heapSize,
                       HeapStrategy.Kind forcedHeap) {
        this.heapSize = heapSize;
        // Before anything is emitted: the entry stub has to know how much room to
        // leave the stack, and the selector has to know which functions to guard.
        this.stack = new StackDepth(module);
        // Which allocator, likewise: the storage words and the startup sequence both
        // depend on it, and it is a question about the whole module.
        this.heap = forcedHeap == null
                ? HeapStrategy.of(module)
                : HeapStrategy.forced(forcedHeap, module);
        // Only a program that can recurse is guarded, and only a whole program has a
        // startup sequence to set the limit in.
        this.guardsStack = withEntry && !stack.recursiveFunctions().isEmpty();
        List<Asm.Line> lines = new ArrayList<>();

        if (withEntry) {
            emitEntry(lines, module.entryFunctionLabel(), module.usesInterrupts());
        }

        InstructionSelector selector = new InstructionSelector(reporter, mangler);
        Set<String> jumpTableTargets = jumpTableTargets(module);
        for (Ir.Function function : module.functions()) {
            // Register allocation is per function body: liveness, an interference
            // graph, then Chaitin-Briggs colouring over A, B and C. D stays the
            // frame pointer.
            GraphColoring.Allocation allocation = null;
            Liveness liveness = null;
            if (optLevel > 0) {
                liveness = new Liveness(function);
                allocation = new GraphColoring(
                        new InterferenceGraph(function, liveness)).allocation();
            }
            boolean guard = withEntry && stack.recursiveFunctions().contains(function.label());
            if (guard) module.runtimeHelpers().add(Runtime.STACK_OVERFLOW);
            // The selector needs liveness too, not just the colouring: choosing a
            // scratch register safely means knowing what the code after an
            // instruction still reads.
            List<Asm.Line> body = selector.select(function, function.slotCount(),
                    mangler.internal("epi_" + function.name()), allocation, guard, liveness);
            // The peephole runs per function: its knowledge of what a register holds
            // must not survive across a function boundary.
            if (optLevel > 0) {
                body = Peephole.optimize(body, selector.temporaryOffsets());
                // After the peephole, since removing a redundant load can be what
                // empties the frame in the first place.
                body = FramePointerElimination.apply(body, jumpTableTargets,
                        Set.of(Runtime.STACK_OVERFLOW, Runtime.HEAP_CORRUPT));
                body = Peephole.optimize(body, selector.temporaryOffsets());
                // Folding runs before the cleanup that removes what it orphans.
                body = FoldLoads.optimize(body);
                body = DeadMoves.optimize(body);
            }
            lines.add(new Asm.Comment("---- " + function.name() + " ----"));
            lines.addAll(body);
        }
        if (module.usesInterrupts()) emitInterruptTrampoline(lines);
        lines.addAll(Runtime.bodiesFor(module.runtimeHelpers(), heap));
        emitGlobals(lines, module);
        if (guardsStack) emitStackLimitStorage(lines);
        if (heapSize != 0) emitHeapStorage(lines);
        checkImageSize(lines);
        return Asm.render(lines);
    }

    /**
     * Every label a jump table can reach, which is where an indirect jump can land.
     *
     * <p>Collected across the whole module because a per-function pass sees the branch
     * but not the table it reads: the tables are data, and data goes after the code.
     */
    private static Set<String> jumpTableTargets(Ir.Module module) {
        Set<String> targets = new HashSet<>();
        for (Ir.JumpTable table : module.jumpTables()) targets.addAll(table.targets());
        return targets;
    }

    /**
     * Globals go after the code. ORG cannot move backwards, and the whole image is
     * loaded into memory anyway, so an initialised global is just a DW or DB in the
     * output and needs no startup code.
     */
    private void emitGlobals(List<Asm.Line> lines, Ir.Module module) {
        if (!module.jumpTables().isEmpty()) {
            lines.add(new Asm.Comment("---- jump tables ----"));
            for (Ir.JumpTable table : module.jumpTables()) {
                lines.add(new Asm.Label(table.label()));
                for (String target : table.targets()) {
                    lines.add(new Asm.Directive("DW", List.of(new Asm.LabelRef(target)), null));
                }
            }
        }
        if (!module.strings().isEmpty()) {
            lines.add(new Asm.Comment("---- strings ----"));
            for (Ir.StringData string : module.strings()) {
                lines.add(new Asm.Label(string.label()));
                if (!string.value().isEmpty()) {
                    lines.add(new Asm.Directive("DB",
                            List.of(new Asm.LabelRef(quote(string.value()))), null));
                }
                lines.add(new Asm.Directive("DB", List.of(new Asm.Imm(0)), "terminator"));
            }
        }
        if (module.globals().isEmpty()) return;

        lines.add(new Asm.Comment("---- globals ----"));
        for (Ir.Global global : module.globals()) {
            lines.add(new Asm.Label(global.label()));
            emitGlobalStorage(lines, global);
        }
    }

    private void emitEntry(List<Asm.Line> lines, String mainLabel, boolean withInterrupts) {
        String start = ".start";
        lines.add(new Asm.Comment("entry: exactly three bytes, so the interrupt"));
        lines.add(new Asm.Comment("vector lands on 0x0003"));
        lines.add(new Asm.Insn("JMP", new Asm.LabelRef(start)));
        lines.add(new Asm.Label(".irq"));
        if (withInterrupts) {
            // JMP is three bytes, so the vector itself is a jump to the trampoline.
            lines.add(new Asm.Insn("JMP", List.of(new Asm.LabelRef(ISR_TRAMPOLINE)), null));
        } else {
            lines.add(new Asm.Insn("IRET"));
        }
        lines.add(new Asm.Label(start));
        lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.SP), new Asm.Imm(INITIAL_SP)),
                "stack grows down from the top of RAM"));
        if (heapSize != 0) emitHeapSetup(lines);
        if (guardsStack) emitStackLimitSetup(lines);
        lines.add(new Asm.Insn("CALL", List.of(new Asm.LabelRef(mainLabel)), null));
        lines.add(new Asm.Insn("HLT", List.of(), "result left in A"));
        lines.add(new Asm.Blank());
    }

    /**
     * How many bytes to keep for the stack.
     *
     * <p>The computed requirement plus a little, or the floor, whichever is larger.
     * A recursive program has no computed requirement and gets the floor.
     */
    private int stackReserve() {
        if (stack == null || !stack.isBounded()) return STACK_RESERVE;
        return Math.max(stack.bound() + STACK_MARGIN, STACK_RESERVE);
    }

    /**
     * Whether it all fits: image, then heap, then stack growing down to meet them.
     *
     * <p>The stack grows down from 0x0FFF towards code growing up from 0 and nothing
     * detects the collision at run time, so the useful thing here is to say how much
     * stack the program actually needs rather than to guess on its behalf.
     */
    private void checkImageSize(List<Asm.Line> lines) {
        int bytes = 0;
        for (Asm.Line line : lines) {
            if (line instanceof Asm.Insn insn) {
                bytes += estimateSize(insn);
            } else if (line instanceof Asm.Directive directive) {
                bytes += estimateSize(directive);
            } else if (line instanceof Asm.Raw raw) {
                bytes += estimateSize(raw);
            }
        }
        imageBytes = bytes;
        if (bytes > RAM_LIMIT) {
            reporter.error(Span.NONE,
                    "the program is about " + bytes + " bytes, which does not fit in "
                            + RAM_LIMIT + " bytes of RAM");
            return;
        }

        int free = RAM_LIMIT - bytes;
        if (stack != null && stack.isBounded()) {
            int needed = stack.bound() + STACK_MARGIN;
            if (needed > free) {
                reporter.error(Span.NONE,
                        "the program is about " + bytes + " bytes and its deepest call "
                                + "chain needs " + needed + " more, which is "
                                + (needed - free) + " bytes past the " + RAM_LIMIT
                                + " available",
                        "the stack grows down into the program and nothing detects the "
                                + "collision at run time");
            }
        } else if (free < STACK_RESERVE) {
            reporter.warning(Span.NONE,
                    "the program is about " + bytes + " bytes, leaving " + free
                            + " for a stack whose depth cannot be bounded",
                    "a recursive call chain has no computed limit; "
                            + STACK_RESERVE + " bytes is about forty frames");
        }
    }

    /**
     * Lays out the heap as one free block spanning the whole region.
     *
     * <p>The base comes from a label emitted after all code and data, so the
     * assembler resolves it to the exact end of the image rather than an estimate.
     * With {@code --heap auto} the size is worked out here, at run time, as the
     * distance from that label to the stack reserve — which is always the largest
     * heap that actually fits, however big the program turned out.
     */
    private void emitHeapSetup(List<Asm.Line> lines) {
        lines.add(new Asm.Comment("heap: " + describeHeap()));

        if (heapSize == Options.HEAP_AUTO) {
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.Imm(INITIAL_SP - stackReserve())),
                    "top of the heap, below what the stack can need"));
        } else {
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.LabelRef(Runtime.HEAP_BASE)), null));
            lines.add(new Asm.Insn("ADD", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.Imm(heapSize)), "the requested size"));
        }
        lines.add(new Asm.Insn("AND", List.of(new Asm.Register(Asm.Reg.A),
                new Asm.Imm(0xFFFE)), "block sizes are even"));
        lines.add(new Asm.Insn("MOV", List.of(new Asm.Absolute(Runtime.HEAP_END),
                new Asm.Register(Asm.Reg.A)), null));

        if (heap.kind() == HeapStrategy.Kind.BUMP) {
            // The frontier starts at the bottom, and that is the whole of the setup:
            // a bump allocator has no layout to work out because it has no metadata.
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.LabelRef(Runtime.HEAP_BASE)), null));
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Absolute(Runtime.HEAP_NEXT),
                    new Asm.Register(Asm.Reg.A)), "nothing handed out yet"));
            return;
        }
        if (heap.kind() == HeapStrategy.Kind.SLAB) {
            // The cell size is the one constant every allocation in the program asks
            // for, so it is known here and the runtime only has to divide by it.
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Absolute(Runtime.SLAB_CELL),
                    new Asm.Imm(heap.cellSize())), "every object is this size"));
        }
        // The region is now known; dividing it up is the runtime's job, because it is
        // arithmetic rather than layout.
        lines.add(new Asm.Insn("CALL", List.of(new Asm.LabelRef(Runtime.HEAP_INIT)), null));
    }

    /** What the heap comment at the top of the image says, so the tier is visible. */
    private String describeHeap() {
        return switch (heap.kind()) {
            case BUMP -> "a bump pointer; this program never frees";
            case SLAB -> "cells of " + heap.cellSize() + " bytes, one bit each";
            case BITMAP -> "two bitmaps over two-byte units";
        };
    }

    /**
     * Works out the lowest address the stack may reach, and stores it.
     *
     * <p>The floor is the top of the heap when there is one, and the end of the image
     * when there is not. The check sits above it by the deepest chain of calls that
     * contains no recursive function — because checks are emitted only in recursive
     * functions, so between one check and the next the stack can still grow by exactly
     * that much, and the check has to fire before the growth rather than after.
     */
    private void emitStackLimitSetup(List<Asm.Line> lines) {
        lines.add(new Asm.Comment("the stack may not grow below here"));
        if (heapSize != 0) {
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.Absolute(Runtime.HEAP_END)), "the heap ends where the stack starts"));
        } else {
            lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.A),
                    new Asm.LabelRef(Runtime.STACK_FLOOR)), "the end of the image"));
        }
        lines.add(new Asm.Insn("ADD", List.of(new Asm.Register(Asm.Reg.A),
                new Asm.Imm(stack.acyclicMargin())),
                "room for the deepest non-recursive chain"));
        lines.add(new Asm.Insn("MOV", List.of(new Asm.Absolute(Runtime.STACK_LIMIT),
                new Asm.Register(Asm.Reg.A)), null));
    }

    /** The limit word, and the floor label if the stack's floor is the image's end. */
    private void emitStackLimitStorage(List<Asm.Line> lines) {
        lines.add(new Asm.Comment("---- stack guard ----"));
        lines.add(new Asm.Label(Runtime.STACK_LIMIT));
        lines.add(new Asm.Directive("DW", List.of(new Asm.Imm(0)), "filled in at startup"));
        if (heapSize == 0) {
            lines.add(new Asm.Comment("the stack's floor: nothing of the program is above this"));
            lines.add(new Asm.Label(Runtime.STACK_FLOOR));
        }
    }

    /** The chosen allocator's state, then the label marking where the region starts. */
    private void emitHeapStorage(List<Asm.Line> lines) {
        lines.add(new Asm.Comment("---- heap ----"));
        for (String label : heapStateLabels()) {
            lines.add(new Asm.Label(label));
            lines.add(new Asm.Directive("DW", List.of(new Asm.Imm(0)), "filled in at startup"));
        }
        lines.add(new Asm.Comment(switch (heap.kind()) {
            case BUMP -> "all of it data: nothing here describes anything";
            case SLAB -> "a bit per cell, and then the cells";
            case BITMAP -> "two bitmaps and then the data, all past here";
        }));
        lines.add(new Asm.Label(Runtime.HEAP_BASE));
    }

    /**
     * The words the chosen allocator keeps, and only those.
     *
     * <p>Each is two bytes out of 4,096 that the heap would otherwise have, so a bump
     * allocator does not reserve a bitmap pointer it will never read.
     */
    private List<String> heapStateLabels() {
        return switch (heap.kind()) {
            case BUMP -> List.of(Runtime.HEAP_END, Runtime.HEAP_NEXT);
            case SLAB -> List.of(Runtime.HEAP_END, Runtime.SLAB_BASE, Runtime.SLAB_CELL,
                    Runtime.SLAB_COUNT, Runtime.SLAB_HINT, Runtime.SLAB_HWORD,
                    Runtime.SLAB_HMASK, Runtime.SLAB_SCRATCH);
            case BITMAP -> List.of(Runtime.HEAP_END, Runtime.HEAP_USED, Runtime.HEAP_BYTES,
                    Runtime.HEAP_DATA, Runtime.HEAP_UNITS, Runtime.HEAP_HINT,
                    Runtime.HEAP_HWORD, Runtime.HEAP_HMASK);
        };
    }

    /**
     * Dispatches an interrupt to whatever handler is currently installed.
     *
     * <p>The CPU pushes only IP and SR on entry, so everything else has to be saved
     * here. All four registers go unconditionally: the handler address is only known
     * at run time, so there is no way to tell what it touches. That is the price of
     * a vector that can be changed while the program runs.
     *
     * <p>Flags may be clobbered freely — SR was pushed by the CPU and IRET restores
     * it — which is what makes the CMP below safe.
     */
    private void emitInterruptTrampoline(List<Asm.Line> lines) {
        String done = ".isr_none";

        lines.add(new Asm.Comment("---- interrupt dispatch ----"));
        lines.add(new Asm.Label(ISR_TRAMPOLINE));
        for (Asm.Reg register : List.of(Asm.Reg.A, Asm.Reg.B, Asm.Reg.C, Asm.Reg.D)) {
            lines.add(new Asm.Insn("PUSH", List.of(new Asm.Register(register)),
                    register == Asm.Reg.A ? "the CPU saved only IP and SR" : null));
        }

        lines.add(new Asm.Insn("MOV", List.of(new Asm.Register(Asm.Reg.B),
                new Asm.Absolute(Lowering.ISR_VECTOR_LABEL)), "the installed handler"));
        lines.add(new Asm.Insn("CMP", List.of(new Asm.Register(Asm.Reg.B), new Asm.Imm(0)), null));
        lines.add(new Asm.Insn("JZ", List.of(new Asm.LabelRef(done)),
                "nothing installed; do not jump to address 0"));
        // CALL [B] transfers to the address IN B; it is not a double indirection.
        lines.add(new Asm.Insn("CALL", List.of(new Asm.Indirect(Asm.Reg.B, 0)), null));

        lines.add(new Asm.Label(done));
        for (Asm.Reg register : List.of(Asm.Reg.D, Asm.Reg.C, Asm.Reg.B, Asm.Reg.A)) {
            lines.add(new Asm.Insn("POP", List.of(new Asm.Register(register)), null));
        }
        lines.add(new Asm.Insn("IRET"));
        lines.add(new Asm.Blank());
    }

    /**
     * Reserves a global's storage, with its starting bytes.
     *
     * <p>The assembler has only DB, DW and ORG, and ORG needs a literal address we
     * do not know at this point — so an aggregate is spelled out a word at a time. It
     * must come to exactly {@code size} bytes: a single DW for a 240-byte array would
     * leave every global declared after it inside the array. A word that holds an
     * address is a DW of the label, which the assembler resolves; the rest is the
     * image, big-endian, a byte at a time only where a word will not do.
     */
    private static void emitGlobalStorage(List<Asm.Line> lines, Ir.Global global) {
        int size = Math.max(1, global.size());
        String comment = size > 2 ? size + " bytes" : null;
        int at = 0;
        while (at < size) {
            String label = global.addresses().get(at);
            if (label != null) {
                lines.add(new Asm.Directive("DW", List.of(new Asm.LabelRef(label)), comment));
                at += 2;
            } else if (at + 1 < size && !global.addresses().containsKey(at + 1)) {
                int word = (byteAt(global, at) << 8) | byteAt(global, at + 1);
                lines.add(new Asm.Directive("DW", List.of(new Asm.Imm(word)), comment));
                at += 2;
            } else {
                lines.add(new Asm.Directive("DB", List.of(new Asm.Imm(byteAt(global, at))), comment));
                at += 1;
            }
            comment = null;
        }
    }

    private static int byteAt(Ir.Global global, int offset) {
        return offset < global.image().length ? global.image()[offset] & 0xFF : 0;
    }

    /**
     * Renders a string as a DB operand.
     *
     * <p>Everything that is not a plain printable character becomes {@code \\xNN},
     * and nothing else is ever emitted, because {@code \\xNN} is the only escape this
     * assembler decodes reliably. Its other six run as
     * {@code .replace(/\\n/, ...)} with <em>no {@code g} flag</em>, so only the first
     * occurrence of each in a string is decoded and every later one survives as two
     * literal bytes; and it has no case for {@code \\\\} at all, so a backslash
     * written that way comes out as two backslashes. Only the {@code \\xNN} pass
     * carries {@code /g}.
     *
     * <p>This was silently corrupting data rather than failing: {@code "a\nb\nc"}
     * put a backslash and an {@code n} in the middle of the string, and the size
     * estimate was a byte short for each one. Anything carrying binary — a tile set, a
     * lookup table — was at risk the moment it held two of the same escape.
     *
     * <p>The order the assembler applies its passes makes this safe: the six named
     * replacements run first and none of them can match inside {@code \\xNN}, then
     * the global hex pass decodes it.
     */
    private static String quote(String value) {
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // Printable ASCII goes through as itself, except the two characters that
            // would end or escape the operand.
            if (c >= 0x20 && c <= 0x7E && c != '"' && c != '\\') {
                sb.append(c);
            } else {
                sb.append(String.format("\\x%02X", (int) c));
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * How many bytes the assembler will emit for a quoted DB operand.
     *
     * <p>Not its length. {@link #quote} writes anything unprintable as {@code \xNN},
     * which is four characters for one byte, and the four short escapes are two
     * characters for one. Counting characters instead made a program carrying real
     * binary data — a tile set, a sprite table, a lookup table — look far larger than
     * it is: {@code snake.mona}'s six 32-byte tiles were counted as 128 bytes each,
     * and the whole program was reported at 3,579 bytes when the assembler produced
     * 3,051.
     *
     * <p>That direction of error is the worse one. An under-estimate emits an image
     * that does not fit and the simulator complains; an over-estimate refuses to
     * compile a program that would have been fine.
     */
    private static int quotedByteLength(String quoted) {
        String body = quoted;
        if (body.length() >= 2 && body.startsWith("\"") && body.endsWith("\"")) {
            body = body.substring(1, body.length() - 1);
        }
        int bytes = 0;
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) != '\\' || i + 1 >= body.length()) {
                bytes++;
                i++;
                continue;
            }
            char kind = body.charAt(i + 1);
            i += 2;
            if (kind == 'x' || kind == 'X') {
                int digits = 0;
                while (digits < 2 && i < body.length() && isHexDigit(body.charAt(i))) {
                    i++;
                    digits++;
                }
            }
            bytes++;
        }
        return bytes;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** How many bytes a data directive contributes to the image. */
    private static int estimateSize(Asm.Directive directive) {
        return switch (directive.name()) {
            case "DB" -> directive.operands().stream()
                    .mapToInt(o -> o instanceof Asm.LabelRef ref
                            // A quoted string, whose escapes are shorter than they look.
                            ? Math.max(1, quotedByteLength(ref.label()))
                            : 1)
                    .sum();
            case "DW" -> 2 * directive.operands().size();
            default -> 0;
        };
    }

    /**
     * The same, for a hand-written line.
     *
     * <p>Runtime helpers are appended as text rather than built as {@link Asm.Insn},
     * so without this the allocator, the signed-arithmetic helpers and the fault
     * reporters were all worth zero bytes — which mattered, because they are the
     * largest single thing a small program carries. {@code heap.mona} was reported at
     * 331 bytes when the assembler produced 1,089.
     */
    private static int estimateSize(Asm.Raw raw) {
        String text = raw.text();
        int comment = text.indexOf(';');
        if (comment >= 0) text = text.substring(0, comment);
        text = text.strip();
        if (text.isEmpty() || text.endsWith(":")) return 0;

        String[] head = text.split("\\s+", 2);
        String mnemonic = head[0].toUpperCase(Locale.ROOT);
        String rest = head.length > 1 ? head[1].strip() : "";

        if (mnemonic.equals("DW")) return 2 * rest.split(",").length;
        if (mnemonic.equals("DB")) {
            // Either a quoted string, whose escapes decode shorter, or a byte each.
            if (rest.startsWith("\"")) return Math.max(1, quotedByteLength(rest));
            return rest.split(",").length;
        }

        int size = 1;
        if (!rest.isEmpty()) {
            boolean byteOp = isByteOperation(mnemonic);
            for (String field : rest.split(",")) {
                String operand = field.strip().toUpperCase(Locale.ROOT);
                boolean small = REGISTERS.contains(operand)
                        || (byteOp && !operand.startsWith("["));
                size += small ? 1 : 2;
            }
        }
        return size;
    }

    /** Operands that cost one byte rather than two; everything else is a word. */
    private static final Set<String> REGISTERS = Set.of(
            "A", "B", "C", "D", "SP", "AH", "AL", "BH", "BL", "CH", "CL", "DH", "DL");

    private static final Set<String> WORD_MNEMONICS = Set.of(
            "MOV", "CMP", "ADD", "SUB", "AND", "OR", "XOR", "NOT", "SHL", "SHR",
            "INC", "DEC", "MUL", "DIV", "PUSH", "POP", "TEST", "NEG");

    /**
     * Whether a mnemonic is the byte form of another, whose immediates are one byte.
     *
     * <p>Asking whether it ends in {@code B} is not enough: {@code SUB} does, and is a
     * word operation. The byte forms are a word mnemonic with a {@code B} appended, so
     * that is what is checked — {@code CMPB} is {@code CMP} plus one, {@code SUB} is
     * not {@code SU} plus one.
     */
    private static boolean isByteOperation(String mnemonic) {
        return mnemonic.length() > 1 && mnemonic.endsWith("B")
                && WORD_MNEMONICS.contains(mnemonic.substring(0, mnemonic.length() - 1));
    }

    /** One opcode byte, plus one per register/byte operand and two per word operand. */
    private static int estimateSize(Asm.Insn insn) {
        boolean byteOp = isByteOperation(insn.mnemonic().toUpperCase(Locale.ROOT));
        int size = 1;
        for (Asm.Operand operand : insn.operands()) {
            size += switch (operand) {
                case Asm.Register ignored -> 1;
                case Asm.Imm ignored -> byteOp ? 1 : 2;
                case Asm.Indirect ignored -> 2;
                case Asm.Absolute ignored -> 2;
                case Asm.LabelRef ignored -> 2;
            };
        }
        return size;
    }
}
