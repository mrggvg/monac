package dev.madlador.codegen;

import dev.madlador.ir.HeapStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Hand-written assembly helpers for operations the machine cannot express.
 *
 * <p>The machine has only unsigned {@code DIV} and a logical {@code SHR}, so signed
 * division, remainder and right shift have to be synthesised. They are too long to
 * inline — signed division alone is about twenty instructions and needs more live
 * scratch than three registers comfortably allow — so they are out-of-line
 * subroutines, appended only when a program actually references one. A program that
 * never uses {@code sword} pays nothing.
 *
 * <p>They follow the ordinary calling convention: argument 0 in {@code B}, any
 * further arguments at {@code [D+5]} upwards, result in {@code A}. Argument 0 is
 * copied to a frame slot on entry, because these bodies use {@code B} as a scratch
 * register throughout. They freely overwrite their stack argument slots, which is
 * safe because the caller discards them with {@code ADD SP} on return, and they
 * clobber {@code B} and {@code C}, which nothing expects to survive a call.
 *
 * <p>Labels are dotted rather than underscore-prefixed: the assembler rejects a
 * label beginning with an underscore outright.
 */
public final class Runtime {

    /** Signed division, truncating toward zero as C does. */
    public static final String SDIV = ".rt_sdiv";

    /** Signed remainder; the result takes the sign of the dividend. */
    public static final String SMOD = ".rt_smod";

    /** Arithmetic right shift, propagating the sign bit. */
    public static final String SAR = ".rt_sar";

    /** {@code void __vwrite(word vaddr, word from, word count)} — RAM into video memory. */
    public static final String VWRITE = ".rt_vwrite";

    /** {@code void __vread(word vaddr, word into, word count)} — video memory into RAM. */
    public static final String VREAD = ".rt_vread";

    /** {@code void __vfill(word vaddr, word value, word count)} — a pattern into video memory. */
    public static final String VFILL = ".rt_vfill";

    /** {@code void __waitframe()} — poll the card's 50 Hz refresh. */
    public static final String WAITFRAME = ".rt_waitframe";

    /** {@code word __mulhi(word a, word b)} — the high half of an unsigned product. */
    public static final String MULHI = ".rt_mulhi";

    /** {@code word __getkey()} — the keypad's status and key, drained correctly. */
    public static final String GETKEY = ".rt_getkey";

    /** {@code void __memcpy(word dst, word src, word count)} — a byte copy, overlap-safe. */
    public static final String MEMCPY = ".rt_memcpy";

    /** {@code void __memset(word dst, word value, word count)} — a byte fill. */
    public static final String MEMSET = ".rt_memset";

    /** {@code word __strlen(byte* s)}. */
    public static final String STRLEN = ".rt_strlen";

    /** {@code void __strcpy(byte* dst, byte* src)}. */
    public static final String STRCPY = ".rt_strcpy";

    /** {@code sword __strcmp(byte* a, byte* b)}. */
    public static final String STRCMP = ".rt_strcmp";

    /** {@code word __sqrt(word n)} — the integer square root, rounded down. */
    public static final String SQRT = ".rt_sqrt";

    /** {@code sword __sin(word angle)}; the body is shared with {@link #COS}. */
    public static final String SIN = ".rt_sin";

    /** {@code sword __cos(word angle)} — falls through into {@link #SIN}. */
    public static final String COS = ".rt_cos";

    /** {@code sword __fixmul(sword a, sword b)} — built on {@link #MULHI}. */
    public static final String FIXMUL = ".rt_fixmul";

    /**
     * The quarter-wave sine table. Undotted because it is loaded as an immediate, and
     * a dotted label is not admitted everywhere a plain one is.
     */
    public static final String SIN_TABLE = "rt_sin_table";

    /** {@code word __alloc(word size)} — first-fit allocation from the heap. */
    public static final String ALLOC = "rt_alloc";

    /** {@code void __free(word pointer)} — clear a block's bits. */
    public static final String FREE = "rt_free";

    /**
     * The first byte of the heap.
     *
     * <p>Emitted after all code and data, so the assembler resolves it to the exact
     * end of the image — no estimating how big the program came out.
     *
     * <p>Undotted, unlike the other runtime labels, and that is not cosmetic: the
     * assembler's bracket syntax is {@code \[(\w+...)\]}, which does not admit a
     * dot, so {@code [.rt_heap_base]} is a syntax error while {@code [rt_heap_base]}
     * assembles.
     */
    public static final String HEAP_BASE = "rt_heap_base";

    /** One past the last heap byte, filled in at startup. */
    public static final String HEAP_END = "rt_heap_end";

    /**
     * The heap's layout, all filled in by {@link #HEAP_INIT} at startup.
     *
     * <p>The region is divided into two bitmaps and the data they describe. Each
     * bitmap holds one bit per two-byte unit, so a unit costs two data bytes and two
     * bits — nine bytes of region for every eight of data.
     *
     * <p>The first bitmap, addressed by this label, says whether a unit belongs to a
     * live block. The second, {@link #HEAP_BYTES} further on, says whether it is the
     * first of one; together they are what let {@code free} take a bare pointer and
     * work out where the block ends. They are kept apart rather than interleaved
     * because {@code alloc} reads only the first, and one bit per unit means a single
     * word covers sixteen of them.
     */
    public static final String HEAP_USED = "rt_heap_used";

    /** @see #HEAP_USED */
    public static final String HEAP_DATA = "rt_heap_data";

    /** @see #HEAP_USED */
    public static final String HEAP_UNITS = "rt_heap_units";

    /**
     * Bytes in one bitmap, which is also the distance between the two.
     *
     * <p>They have identical geometry, so a unit's bit sits at the same offset
     * within each and one address computation serves for both.
     */
    public static final String HEAP_BYTES = "rt_heap_bytes";

    /**
     * The lowest unit that could possibly be free.
     *
     * <p>Not a rover and not a policy: first fit from zero would examine exactly the
     * same units, because every unit below the hint is known to be in use. {@code
     * alloc} sets it past the block it just placed — everything below that is busy,
     * by induction — and {@code free} lowers it to any unit it releases. Without it a
     * heap holding n blocks re-reads all of them on every call, which is the
     * quadratic the free list was abandoned for.
     */
    public static final String HEAP_HINT = "rt_heap_hint";

    /**
     * Where the hint's bit lives: its word in {@code used}, and its mask.
     *
     * <p>Both are already in registers at the moment the hint moves — {@code alloc}
     * finishes marking on exactly the unit that becomes the new hint — so remembering
     * them costs two stores and saves the address computation on the next call.
     */
    public static final String HEAP_HWORD = "rt_heap_hword";

    /** @see #HEAP_HWORD */
    public static final String HEAP_HMASK = "rt_heap_hmask";

    /** Divides the region into bitmaps and data; called once, from the entry stub. */
    public static final String HEAP_INIT = "rt_heap_init";

    /** Where {@code free} goes when handed something that never came from the heap. */
    public static final String HEAP_CORRUPT = ".rt_heap_corrupt";

    /**
     * The frontier: the first byte never yet handed out.
     *
     * <p>The whole of the bump allocator's state, and where a program that never
     * frees keeps its heap. Set to {@link #HEAP_BASE} at startup and never reset.
     */
    public static final String HEAP_NEXT = "rt_heap_next";

    /** The slab's layout, all filled in by {@link #HEAP_INIT}. @see #SLAB_CELL */
    public static final String SLAB_BASE = "rt_slab_base";

    /**
     * Bytes in one cell: the size every allocation in the program asks for.
     *
     * <p>Kept in memory rather than baked into the code, because an operand read is
     * the same single tick as an immediate on this machine, and a word of data is
     * cheaper than a body that has to be rewritten per program.
     */
    public static final String SLAB_CELL = "rt_slab_cell";

    /** How many cells the region holds. @see #SLAB_CELL */
    public static final String SLAB_COUNT = "rt_slab_count";

    /**
     * The lowest cell that might be free, and where its bit lives.
     *
     * <p>The same memoized prefix the bitmap allocator uses: every cell below the
     * hint is live, so first fit from zero would examine exactly these cells anyway.
     */
    public static final String SLAB_HINT = "rt_slab_hint";

    /** @see #SLAB_HINT */
    public static final String SLAB_HWORD = "rt_slab_hword";

    /** @see #SLAB_HINT */
    public static final String SLAB_HMASK = "rt_slab_hmask";

    /** Scratch for {@code free}, which has three registers and four things to hold. */
    public static final String SLAB_SCRATCH = "rt_slab_scratch";

    /**
     * The lowest address the stack may reach, filled in at startup.
     *
     * <p>Undotted for the same reason as the heap labels: it is read as
     * {@code [rt_stack_limit]}, and the assembler's bracket syntax admits no dot.
     */
    public static final String STACK_LIMIT = "rt_stack_limit";

    /** The end of the image, where the stack's floor sits when there is no heap. */
    public static final String STACK_FLOOR = "rt_stack_floor";

    /** Where a function jumps when its own frame would cross the limit. */
    public static final String STACK_OVERFLOW = ".rt_stack_overflow";

    private Runtime() {
    }

    private static final String SDIV_BODY = """
            .rt_sdiv:
                    PUSH D
                    MOV D, SP
                    SUB SP, 4               ; [D-1] the sign of the result, [D-3] the dividend
                    MOV [D-3], B            ; argument 0 arrives in a register
                    MOV A, [D-3]
                    XOR A, [D+5]
                    AND A, 32768            ; negative only if the signs differ
                    MOV [D-1], A
                    MOV A, [D-3]
                    AND A, 32768
                    JZ .sdiv_a
                    MOV A, 0
                    SUB A, [D-3]            ; a = -a
                    MOV [D-3], A
            .sdiv_a:
                    MOV A, [D+5]
                    AND A, 32768
                    JZ .sdiv_b
                    MOV A, 0
                    SUB A, [D+5]            ; b = -b
                    MOV [D+5], A
            .sdiv_b:
                    MOV A, [D-3]
                    DIV [D+5]               ; unsigned divide of the magnitudes
                    MOV B, [D-1]
                    CMP B, 0
                    JZ .sdiv_end
                    MOV B, A
                    MOV A, 0
                    SUB A, B                ; apply the sign
            .sdiv_end:
                    MOV SP, D
                    POP D
                    RET
            """;

    private static final String SMOD_BODY = """
            .rt_smod:
                    PUSH D
                    MOV D, SP
                    SUB SP, 4               ; [D-1] the sign of the dividend, [D-3] the value
                    MOV [D-3], B            ; argument 0 arrives in a register
                    MOV A, [D-3]
                    AND A, 32768
                    MOV [D-1], A
                    JZ .smod_a
                    MOV A, 0
                    SUB A, [D-3]
                    MOV [D-3], A
            .smod_a:
                    MOV A, [D+5]
                    AND A, 32768
                    JZ .smod_b
                    MOV A, 0
                    SUB A, [D+5]
                    MOV [D+5], A
            .smod_b:
                    MOV A, [D-3]
                    DIV [D+5]
                    MUL [D+5]
                    MOV B, A
                    MOV A, [D-3]
                    SUB A, B                ; a - (a / b) * b
                    MOV B, [D-1]
                    CMP B, 0
                    JZ .smod_end
                    MOV B, A
                    MOV A, 0
                    SUB A, B                ; the remainder follows the dividend
            .smod_end:
                    MOV SP, D
                    POP D
                    RET
            """;

    private static final String SAR_BODY = """
            .rt_sar:
                    PUSH D
                    MOV D, SP
                    SUB SP, 2               ; [D-1] the value to shift
                    MOV [D-1], B            ; argument 0 arrives in a register
                    MOV A, [D+5]
                    AND A, 15               ; a shift past the width is meaningless
                    MOV C, A
                    MOV A, [D-1]
                    SHR A, C                ; SHR is logical, so it zero-fills
                    MOV B, [D-1]
                    AND B, 32768
                    JZ .sar_end
                    CMP C, 0
                    JZ .sar_end
                    MOV B, 16
                    SUB B, C
                    MOV C, 65535
                    SHL C, B                ; mask of the vacated high bits
                    OR A, C                 ; fill them with the sign
            .sar_end:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * First fit over the {@code used} bitmap.
     *
     * <p>A run of free units is a run of zero bits, so there is no free list to walk,
     * nothing to split, and nothing to coalesce: space that has been released is
     * already contiguous with its neighbours, because contiguity is a property of the
     * array rather than something to be rediscovered afterwards. That is most of what
     * the old allocator's code was for.
     *
     * <p>The scan counts consecutive zero bits and stops once it has enough. Marking
     * sets them in {@code used}, and the first of them in {@code start}, which is what
     * lets {@code free} find the block's end again from nothing but a pointer.
     */
    /**
     * Divides the heap region into two bitmaps and the data area, and clears them.
     *
     * <p>Each unit costs two data bytes and two bits, so of every nine bytes of region
     * eight are data — {@code units = region * 4 / 9}. Each bitmap is then rounded up
     * to a whole number of words, and the unit count recomputed from what is actually
     * left over, clamped to what the bitmaps can describe: rounding is allowed to cost
     * a unit, never to promise one that has no bit.
     *
     * <p>It does not zero the bitmaps, because the machine hands them over zeroed. A
     * fresh {@code MemoryCell} holds zero and the simulator's reset writes zero to
     * every cell outside a device region, while assembling only stores the image over
     * the bytes the image occupies — and a halted CPU cannot be restarted without that
     * same reset. So the region above the image is zero every time this runs. Clearing
     * it cost a tenth of {@code heap.mona}, all of it spent overwriting zeroes with
     * zeroes; a machine that did not promise this would need the loop back.
     */
    private static final String HEAP_INIT_BODY = """
            rt_heap_init:
                    MOV A, [rt_heap_end]
                    SUB A, rt_heap_base
                    SHL A, 2
                    MOV B, 9
                    DIV B                   ; units = region * 4 / 9
                    ADD A, 15
                    SHR A, 4
                    SHL A, 1                ; bytes in one bitmap, rounded up to a word
                    MOV C, A
                    MOV [rt_heap_bytes], A
                    MOV A, rt_heap_base
                    MOV [rt_heap_used], A
                    ADD A, C
                    ADD A, C
                    MOV [rt_heap_data], A   ; the data follows both bitmaps
                    MOV B, A
                    MOV A, [rt_heap_end]
                    SUB A, B
                    SHR A, 1                ; units the data area holds
                    MOV B, C
                    SHL B, 3                ; units the bitmaps can describe
                    CMP A, B
                    JC .hi_units
                    MOV A, B
            .hi_units:
                    MOV [rt_heap_units], A
                    MOV A, 0
                    MOV [rt_heap_hint], A   ; unit zero, whose bit is the first of all
                    MOV A, rt_heap_base
                    MOV [rt_heap_hword], A
                    MOV A, 1
                    MOV [rt_heap_hmask], A
                    RET                     ; the bitmaps are already zero: see above
            """;

    /**
     * Where one unit's bit lives: takes the unit in {@code A} and a bitmap's address in
     * {@code B}, and returns the word holding that bit in {@code B} and its mask in
     * {@code C}.
     *
     * <p>Called once at the head of each operation and never inside a loop. Every loop
     * here walks consecutive units, and consecutive units are a mask shifted left with
     * the word address stepped on the sixteenth — far cheaper than recomputing an
     * address that is already known.
     */
    private static final String BITREF_BODY = """
            .rt_bitref:
                    MOV C, A
                    SHR A, 4                ; sixteen units to a word
                    SHL A, 1
                    ADD A, B
                    MOV B, A                ; B = the word holding the bit
                    MOV A, C
                    AND A, 15
                    MOV C, A
                    MOV A, 1
                    SHL A, C
                    MOV C, A                ; C = the bit's mask
                    RET
            """;

    /**
     * First fit over the {@code used} bitmap.
     *
     * <p>A run of free units is a run of zero bits, so there is no free list to walk,
     * nothing to split, and nothing to coalesce: released space is already contiguous
     * with its neighbours, because contiguity is a property of the array rather than
     * something to be rediscovered afterwards. That is most of what the old
     * allocator's code was for.
     *
     * <p>The scan begins at {@link #HEAP_HINT}, below which nothing is free, and carries
     * the bitmap word in {@code B} and the bit in {@code C} rather than recomputing
     * them. On a word boundary with sixteen units still ahead it takes the shortcut
     * that one bit per unit exists for: a word of zero is sixteen free units and a word
     * of ones is sixteen to skip, one compare instead of sixteen.
     */
    private static final String ALLOC_BODY = """
            rt_alloc:
                    PUSH D
                    MOV D, SP
                    SUB SP, 6           ; [D-1] units wanted, [D-3] cursor, [D-5] run start
                    MOV A, B                ; argument 0 arrives in a register
                    CMP A, 0
                    JZ .alloc_fail
                    ADD A, 1
                    SHR A, 1            ; two bytes to a unit, rounding up
                    MOV [D-1], A
                    MOV A, [rt_heap_hint]
                    MOV [D-3], A        ; cursor: the unit being examined
                    MOV [D-5], A        ; where the current run of free units began
                    MOV B, [rt_heap_hword]
                    MOV C, [rt_heap_hmask]  ; its bit, already known, walked on from here
            .alloc_scan:
                    MOV A, [D-3]
                    CMP A, [rt_heap_units]
                    JNC .alloc_fail     ; off the end without ever finding room
                    CMP C, 1
                    JNZ .alloc_bit      ; mid-word, so no shortcut applies
                    ADD A, 16
                    CMP A, [rt_heap_units]
                    JA .alloc_bit       ; fewer than sixteen units left
                    MOV A, [B]
                    CMP A, 0
                    JZ .alloc_word_free
                    CMP A, 65535
                    JNZ .alloc_bit
                    MOV A, [D-3]        ; sixteen busy units: the run restarts past them
                    ADD A, 16
                    MOV [D-3], A
                    MOV [D-5], A
                    ADD B, 2
                    JMP .alloc_scan
            .alloc_word_free:
                    MOV A, [D-3]        ; sixteen free units at once
                    ADD A, 16
                    MOV [D-3], A
                    ADD B, 2
                    SUB A, [D-5]
                    CMP A, [D-1]
                    JNC .alloc_found
                    JMP .alloc_scan
            .alloc_bit:
                    MOV A, [B]
                    AND A, C
                    JNZ .alloc_bit_busy
                    MOV A, [D-3]
                    INC A
                    MOV [D-3], A
                    SUB A, [D-5]
                    CMP A, [D-1]
                    JNC .alloc_found
                    JMP .alloc_step
            .alloc_bit_busy:
                    MOV A, [D-3]
                    INC A
                    MOV [D-3], A
                    MOV [D-5], A        ; the run restarts after this one
            .alloc_step:
                    SHL C, 1
                    JNZ .alloc_scan
                    MOV C, 1            ; the bit walked off the end of the word
                    ADD B, 2
                    JMP .alloc_scan
            .alloc_found:
                    MOV A, [D-5]
                    MOV B, [rt_heap_used]
                    CALL .rt_bitref     ; the one bit, in both bitmaps
                    MOV A, B
                    MOV [D-3], A
                    ADD A, [rt_heap_bytes]
                    MOV B, A
                    MOV A, [B]
                    OR A, C
                    MOV [B], A          ; this unit begins the block
                    MOV B, [D-3]
                    MOV A, [D-1]
                    MOV [D-3], A        ; reuse the cursor to count units still to mark
            .alloc_mark:
                    MOV A, [B]
                    OR A, C
                    MOV [B], A
                    SHL C, 1
                    JNZ .alloc_marked
                    MOV C, 1
                    ADD B, 2
            .alloc_marked:
                    MOV A, [D-3]
                    DEC A
                    MOV [D-3], A
                    JNZ .alloc_mark
                    MOV A, [D-5]        ; marking ended on the unit past the block, and
                    ADD A, [D-1]        ; nothing below that can be free
                    MOV [rt_heap_hint], A
                    MOV [rt_heap_hword], B
                    MOV [rt_heap_hmask], C
                    MOV A, [D-5]
                    SHL A, 1
                    ADD A, [rt_heap_data]
                    JMP .alloc_end
            .alloc_fail:
                    MOV A, 0
            .alloc_end:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * Clears the block's bits, and nothing else.
     *
     * <p>No scan and no merging: the released units become zeroes in {@code used} and
     * are part of whatever free run surrounds them the moment they are cleared. Where
     * the old free list cost about fifteen instructions per block in the heap on every
     * call, this costs one pass over the block's own units — two iterations for the
     * six-byte objects this machine actually allocates.
     *
     * <p>The three checks in front of it turn freeing a local's address — which used
     * to corrupt the heap in silence — into a message on the display. A pointer from
     * {@code alloc} lies inside the data area, is even, and has its start bit set; one
     * that does not fails at least one of the three.
     */
    private static final String FREE_BODY = """
            rt_free:
                    PUSH D
                    MOV D, SP
                    SUB SP, 6           ; [D-1] units left, [D-3] the used word, [D-5] the unit
                    MOV A, B                ; argument 0 arrives in a register
                    CMP A, 0
                    JZ .free_done       ; freeing nothing is allowed
                    CMP A, [rt_heap_data]
                    JC .rt_heap_corrupt
                    CMP A, [rt_heap_end]
                    JNC .rt_heap_corrupt
                    SUB A, [rt_heap_data]
                    MOV C, A
                    AND C, 1
                    JNZ .rt_heap_corrupt    ; an odd pointer never came from alloc
                    SHR A, 1                ; the block's first unit
                    MOV [D-5], A
                    MOV C, [rt_heap_units]
                    SUB C, A
                    MOV [D-1], C            ; units between this one and the end
                    MOV B, [rt_heap_used]
                    CALL .rt_bitref         ; B = its word, C = its bit
                    MOV A, B
                    MOV [D-3], A
                    ADD A, [rt_heap_bytes]
                    MOV B, A                ; the same bit, one bitmap along
                    MOV A, [B]
                    AND A, C
                    JZ .rt_heap_corrupt     ; nor does one that starts no block
                    MOV A, C
                    NOT A
                    AND A, [B]
                    MOV [B], A              ; it starts one no longer
                    MOV B, [D-3]
                    MOV A, [D-5]            ; only now, with the pointer accepted:
                    CMP A, [rt_heap_hint]
                    JNC .free_clear
                    MOV [rt_heap_hint], A   ; the next scan starts no later than here,
                    MOV [rt_heap_hword], B  ; and its bit is the one just worked out
                    MOV [rt_heap_hmask], C
            .free_clear:
                    MOV A, C
                    NOT A
                    AND A, [B]
                    MOV [B], A          ; and this unit is no longer in use
                    MOV A, [D-1]
                    DEC A
                    MOV [D-1], A
                    JZ .free_done       ; the block ran to the end of the heap
                    SHL C, 1
                    JNZ .free_next
                    MOV C, 1
                    ADD B, 2
            .free_next:
                    MOV A, B
                    ADD A, [rt_heap_bytes]
                    MOV A, [A]
                    AND A, C
                    JNZ .free_done      ; the next unit begins another block
                    MOV A, [B]
                    AND A, C
                    JNZ .free_clear     ; still inside this one
            .free_done:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * A bump pointer, for a program that never frees.
     *
     * <p>Ten instructions including the call, against the bitmap allocator's hundred
     * and twenty-four, because there is nothing to search and nothing to record. The
     * frontier moves and that is the entire data structure.
     *
     * <p>Nothing rounds the size: this machine has no alignment requirement —
     * {@code loadWord} reads any address — and with no {@code free} there is no
     * odd-pointer check that an odd block would make dishonest.
     *
     * <p>No frame, so it costs the stack only its return address.
     */
    private static final String BUMP_ALLOC_BODY = """
            rt_alloc:
                    MOV A, [rt_heap_next]   ; the block, if it fits
                    MOV C, A
                    ADD C, B                ; argument 0 arrives in a register
                    JC .bump_full           ; the addition itself wrapped
                    CMP C, [rt_heap_end]
                    JA .bump_full
                    MOV [rt_heap_next], C
                    RET
            .bump_full:
                    MOV A, 0
                    RET
            """;

    /**
     * Fixed cells of one size, with one bit each saying whether the cell is live.
     *
     * <p>This is the bitmap allocator with the unit made equal to the object: where
     * that one spends a bit per two bytes and has to find and mark a *run* of them,
     * this spends a bit per cell and needs exactly one. The scan leaves the bitmap
     * word in {@code B} and the bit in {@code C}, so marking is three instructions
     * rather than an address computation and a loop.
     *
     * <p>musl's allocator groups same-size slots behind a bitmap for the same reason.
     * The difference is that it picks the size class when the program runs and we pick
     * it when the program is compiled, so there is only ever one group.
     *
     * <p>A free list threaded through the free cells would be faster still — a pop and
     * a push — but it is what makes a double free silent in C, and one bit per cell
     * keeps that detectable for a twelfth of what the general allocator's bitmap costs.
     *
     * <p>No frame anywhere: the scan keeps its index in memory, which on this machine
     * is the same single tick as a register and leaves all three free for the bitmap
     * word, its mask, and the value being tested.
     */
    private static final String SLAB_ALLOC_BODY = """
            rt_alloc:
                    MOV A, [rt_slab_hint]
                    CMP A, [rt_slab_count]
                    JNC .slab_full          ; every cell is live
                    MOV B, [rt_slab_hword]
                    MOV C, [rt_slab_hmask]
            .slab_scan:
                    MOV A, [B]
                    AND A, C
                    JZ .slab_take
                    MOV A, [rt_slab_hint]
                    INC A
                    MOV [rt_slab_hint], A
                    CMP A, [rt_slab_count]
                    JNC .slab_full
                    SHL C, 1
                    JNZ .slab_scan
                    MOV C, 1                ; the bit walked off the end of the word
                    ADD B, 2
                    JMP .slab_scan
            .slab_take:
                    MOV A, [B]
                    OR A, C
                    MOV [B], A              ; the cell is live from here
                    MOV A, [rt_slab_hint]
                    MOV [rt_slab_scratch], A
                    INC A
                    MOV [rt_slab_hint], A   ; and nothing below it can be free
                    SHL C, 1
                    JNZ .slab_at
                    MOV C, 1
                    ADD B, 2
            .slab_at:
                    MOV [rt_slab_hword], B
                    MOV [rt_slab_hmask], C
                    MOV A, [rt_slab_scratch]
                    MUL [rt_slab_cell]      ; cells are an array, so this is an index
                    ADD A, [rt_slab_base]
                    RET
            .slab_full:
                    MOV A, 0
                    RET
            """;

    /**
     * Clears one cell's bit, having first established that it is a cell.
     *
     * <p>A pointer from {@code alloc} lies inside the cell array, sits exactly on a
     * cell boundary, and has its bit set. The three together rule out a local's
     * address, a pointer into the middle of an object, and freeing the same cell
     * twice — the last of which a free list could not have caught at all.
     *
     * <p>There is no remainder from {@code DIV}, so squareness is checked by
     * multiplying the index back and comparing: two instructions, both a single tick.
     */
    private static final String SLAB_FREE_BODY = """
            rt_free:
                    MOV A, B                ; argument 0 arrives in a register
                    CMP A, 0
                    JZ .slab_free_done      ; freeing nothing is allowed
                    CMP A, [rt_slab_base]
                    JC .rt_heap_corrupt
                    CMP A, [rt_heap_end]
                    JNC .rt_heap_corrupt
                    SUB A, [rt_slab_base]
                    MOV [rt_slab_scratch], A
                    DIV [rt_slab_cell]
                    MOV B, A                ; the index; MUL clobbers only A
                    MUL [rt_slab_cell]
                    CMP A, [rt_slab_scratch]
                    JNZ .rt_heap_corrupt    ; not on a cell boundary
                    MOV A, B
                    CMP A, [rt_slab_count]
                    JNC .rt_heap_corrupt    ; past the last cell
                    MOV [rt_slab_scratch], A
                    MOV B, rt_heap_base     ; the bitmap sits at the front of the region
                    CALL .rt_bitref
                    MOV A, [B]
                    AND A, C
                    JZ .rt_heap_corrupt     ; the cell was already free
                    MOV A, C
                    NOT A
                    AND A, [B]
                    MOV [B], A              ; and now it is
                    MOV A, [rt_slab_scratch]
                    CMP A, [rt_slab_hint]
                    JNC .slab_free_done
                    MOV [rt_slab_hint], A   ; the next scan starts no later than here
                    MOV [rt_slab_hword], B
                    MOV [rt_slab_hmask], C
            .slab_free_done:
                    RET
            """;

    /**
     * Divides the region into a bit per cell and the cells themselves.
     *
     * <p>A cell costs its own bytes and one bit, so of every {@code 8 * cell + 1} bits
     * of region, {@code 8 * cell} are usable. The count is then clamped to what the
     * cells actually fit in after the bitmap is rounded up to a whole word, so that
     * rounding can only cost a cell rather than promise one with no bit.
     *
     * <p>Like {@link #HEAP_INIT_BODY} it does not zero the bitmap, for the same
     * reason: the machine hands the region over zeroed.
     */
    private static final String SLAB_INIT_BODY = """
            rt_heap_init:
                    MOV A, [rt_slab_cell]
                    SHL A, 3
                    INC A
                    MOV C, A                ; bits of region per cell
                    MOV A, [rt_heap_end]
                    SUB A, rt_heap_base
                    SHL A, 3                ; bits of region, which cannot overflow
                    DIV C                   ; cells it could hold
                    ADD A, 15
                    SHR A, 4
                    SHL A, 1                ; bitmap bytes, rounded up to a word
                    MOV C, A
                    MOV A, rt_heap_base
                    ADD A, C
                    MOV [rt_slab_base], A   ; the cells follow the bitmap
                    MOV C, A
                    MOV A, [rt_heap_end]
                    SUB A, C
                    DIV [rt_slab_cell]      ; cells that really fit
                    MOV [rt_slab_count], A
                    MOV A, 0
                    MOV [rt_slab_hint], A
                    MOV A, rt_heap_base
                    MOV [rt_slab_hword], A
                    MOV A, 1
                    MOV [rt_slab_hmask], A
                    RET
            """;

    /**
     * A byte copy that survives overlap.
     *
     * <p>Argument 0, the destination, arrives in {@code B}; the source and the count
     * are read straight off the stack, with no frame, because three registers are
     * exactly enough. When the source is below the destination a forward copy would
     * overwrite bytes before it reads them, so that case runs backwards from the top.
     * The count's slot is reused for where the run stops.
     */
    private static final String MEMCPY_BODY = """
            .rt_memcpy:
                    MOV C, [SP+3]           ; the source
                    CMP C, B
                    JC .mcpy_back           ; source below destination: go backwards
                    MOV A, [SP+5]
                    ADD A, B
                    MOV [SP+5], A           ; where the destination stops
            .mcpy_fwd:
                    CMP B, [SP+5]
                    JNC .mcpy_done
                    MOVB AL, [C]
                    MOVB [B], AL
                    INC B
                    INC C
                    JMP .mcpy_fwd
            .mcpy_back:
                    MOV A, [SP+5]
                    CMP A, 0
                    JZ .mcpy_done
                    MOV [SP+5], B           ; where the destination stops, from above
                    ADD B, A
                    ADD C, A
            .mcpy_bloop:
                    DEC B
                    DEC C
                    MOVB AL, [C]
                    MOVB [B], AL
                    CMP B, [SP+5]
                    JNZ .mcpy_bloop
            .mcpy_done:
                    RET
            """;

    /** A byte fill. Only the low byte of the value is stored, as C's {@code memset}. */
    private static final String MEMSET_BODY = """
            .rt_memset:
                    MOV C, [SP+5]
                    ADD C, B                ; where it stops
                    MOV A, [SP+3]           ; the value; AL is what gets stored
            .mset_loop:
                    CMP B, C
                    JNC .mset_done
                    MOVB [B], AL
                    INC B
                    JMP .mset_loop
            .mset_done:
                    RET
            """;

    /** Walks to the terminator and returns how far it went. */
    private static final String STRLEN_BODY = """
            .rt_strlen:
                    MOV C, B                ; where it started
            .slen_loop:
                    MOVB AL, [B]
                    CMPB AL, 0
                    JZ .slen_done
                    INC B
                    JMP .slen_loop
            .slen_done:
                    MOV A, B
                    SUB A, C
                    RET
            """;

    /** Copies up to and including the terminator; the test comes after the store. */
    private static final String STRCPY_BODY = """
            .rt_strcpy:
                    MOV C, [SP+3]           ; the source
            .scpy_loop:
                    MOVB AL, [C]
                    MOVB [B], AL
                    INC B
                    INC C
                    CMPB AL, 0
                    JNZ .scpy_loop
                    RET
            """;

    /**
     * The first difference between two strings, as C's {@code strcmp}.
     *
     * <p>Both bytes are held at once, in {@code AL} and {@code AH}, so the loop needs
     * no memory traffic beyond the two loads. On a difference they are widened as
     * unsigned and subtracted, which gives -255 to 255: the sign is the order and the
     * magnitude is what C's reference implementation returns too.
     */
    private static final String STRCMP_BODY = """
            .rt_strcmp:
                    MOV C, [SP+3]
            .scmp_loop:
                    MOVB AL, [B]
                    MOVB AH, [C]
                    CMPB AL, AH
                    JNZ .scmp_differ
                    CMPB AL, 0
                    JZ .scmp_equal          ; both ended together
                    INC B
                    INC C
                    JMP .scmp_loop
            .scmp_differ:
                    MOVB CL, AH
                    MOVB CH, 0
                    MOVB AH, 0
                    SUB A, C
                    RET
            .scmp_equal:
                    MOV A, 0
                    RET
            """;

    /**
     * The integer square root, one bit of the answer per step.
     *
     * <p>The classic digit-by-digit method: {@code bit} walks down the powers of four
     * from the largest not above {@code n}, and each step decides one bit of the root
     * by whether {@code root + bit} still fits in what is left of {@code n}. No
     * multiply and no divide, which matters on a machine whose {@code DIV} faults on
     * zero, and at most eight steps. The root lives in the one stack slot, because
     * {@code n}, {@code bit} and a scratch fill the three registers.
     */
    private static final String SQRT_BODY = """
            .rt_sqrt:
                    SUB SP, 2               ; [SP+1] the root so far
                    MOV [SP+1], 0
                    MOV C, 16384            ; the largest power of four in a word
            .sqrt_scale:
                    CMP B, C
                    JNC .sqrt_loop          ; start at the largest one not above n
                    SHR C, 2
                    JMP .sqrt_scale
            .sqrt_loop:
                    CMP C, 0
                    JZ .sqrt_done
                    MOV A, [SP+1]
                    ADD A, C
                    CMP B, A
                    JC .sqrt_zero           ; this bit of the root is 0
                    SUB B, A
                    MOV A, [SP+1]
                    SHR A, 1
                    ADD A, C
                    MOV [SP+1], A
                    SHR C, 2
                    JMP .sqrt_loop
            .sqrt_zero:
                    MOV A, [SP+1]
                    SHR A, 1
                    MOV [SP+1], A
                    SHR C, 2
                    JMP .sqrt_loop
            .sqrt_done:
                    MOV A, [SP+1]
                    ADD SP, 2
                    RET
            """;

    /**
     * Sine and cosine from one quarter of a wave.
     *
     * <p>A quarter turn is 64 steps and the table holds 65 values, 0 to 64 inclusive,
     * because the peak — exactly 256 — has to be in it. The other three quarters are
     * reflections: bit 6 of the angle says to read the table backwards and bit 7 says
     * to negate. Cosine is the same body entered a quarter turn earlier, so it costs
     * one instruction and no second table.
     */
    private static final String SIN_BODY = """
            .rt_cos:
                    ADD B, 64               ; a quarter turn on
            .rt_sin:
                    AND B, 255              ; 256 steps to the turn
                    MOV C, B
                    AND C, 63               ; the step within its quarter
                    MOV A, B
                    AND A, 64
                    JZ .sin_rising
                    MOV A, 64
                    SUB A, C
                    MOV C, A                ; the second and fourth quarters read backwards
            .sin_rising:
                    SHL C, 1
                    MOV A, rt_sin_table
                    ADD C, A
                    MOV A, [C]
                    AND B, 128
                    JZ .sin_done            ; the first half-turn is positive
                    MOV C, A
                    MOV A, 0
                    SUB A, C
            .sin_done:
                    RET
            rt_sin_table:
            """ + sineTable();

    /**
     * {@code sin(i / 256 of a turn) * 256}, rounded, for i from 0 to 64.
     *
     * <p>Computed rather than written out so the numbers cannot be mistyped, and with
     * {@code StrictMath} so they are the same on every JVM. One {@code DW} per line:
     * the assembler takes a single operand and silently drops the rest.
     */
    private static String sineTable() {
        StringBuilder table = new StringBuilder();
        for (int i = 0; i <= 64; i++) {
            long value = Math.round(StrictMath.sin(i * Math.PI / 128) * 256);
            table.append("        DW ").append(value).append('\n');
        }
        return table.toString();
    }

    /**
     * The signed product's bits 8 to 23, which is {@code (a * b) >> 8} for two numbers
     * with eight fractional bits.
     *
     * <p>The low half comes from {@code MUL} and the high half from {@link #MULHI},
     * which reads both operands as unsigned. Read that way a negative {@code a} is
     * {@code a + 65536}, which adds {@code b} to the high half; subtracting it back,
     * and the same for {@code b}, gives the signed high half. Taking bits 8 to 23 of a
     * two's-complement product is an arithmetic shift, so the result rounds toward
     * negative infinity with no sign handling at all.
     */
    private static final String FIXMUL_BODY = """
            .rt_fixmul:
                    PUSH D
                    MOV D, SP
                    SUB SP, 4               ; [D-1] a, [D-3] the low half of the product
                    MOV [D-1], B
                    MOV A, B
                    MUL [D+5]
                    MOV [D-3], A
                    MOV A, [D+5]
                    PUSH A                  ; b, the second argument; a is still in B
                    CALL .rt_mulhi          ; the high half, both read unsigned
                    ADD SP, 2
                    MOV C, [D-1]
                    AND C, 32768
                    JZ .fmul_a
                    SUB A, [D+5]            ; a was negative: take b back out
            .fmul_a:
                    MOV C, [D+5]
                    AND C, 32768
                    JZ .fmul_b
                    SUB A, [D-1]            ; b was negative: take a back out
            .fmul_b:
                    SHL A, 8                ; bits 16 to 23 on top
                    MOV C, [D-3]
                    SHR C, 8                ; bits 8 to 15 below them
                    OR A, C
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * The assembly for the helpers a module actually references, in a stable order.
     *
     * <p>{@code strategy} decides which allocator is emitted, and only one ever is.
     * Which one is a property of the whole program rather than of any call site, so
     * it is settled before this and handed in.
     */
    public static List<Asm.Line> bodiesFor(Set<String> referenced, HeapStrategy strategy) {
        List<Asm.Line> lines = new ArrayList<>();
        if (referenced.isEmpty()) return lines;

        lines.add(new Asm.Comment("---- runtime helpers ----"));
        if (referenced.contains(SDIV)) append(lines, SDIV_BODY);
        if (referenced.contains(SMOD)) append(lines, SMOD_BODY);
        if (referenced.contains(SAR)) append(lines, SAR_BODY);
        if (referenced.contains(MULHI) || referenced.contains(FIXMUL)) append(lines, MULHI_BODY);
        if (referenced.contains(FIXMUL)) append(lines, FIXMUL_BODY);
        if (referenced.contains(VWRITE)) append(lines, VWRITE_BODY);
        if (referenced.contains(VREAD)) append(lines, VREAD_BODY);
        if (referenced.contains(VFILL)) append(lines, VFILL_BODY);
        if (referenced.contains(WAITFRAME)) append(lines, WAITFRAME_BODY);
        if (referenced.contains(GETKEY)) append(lines, GETKEY_BODY);
        if (referenced.contains(MEMCPY)) append(lines, MEMCPY_BODY);
        if (referenced.contains(MEMSET)) append(lines, MEMSET_BODY);
        if (referenced.contains(STRLEN)) append(lines, STRLEN_BODY);
        if (referenced.contains(STRCPY)) append(lines, STRCPY_BODY);
        if (referenced.contains(STRCMP)) append(lines, STRCMP_BODY);
        if (referenced.contains(SQRT)) append(lines, SQRT_BODY);
        // One body with two entry points, so the table is emitted once for either.
        if (referenced.contains(SIN) || referenced.contains(COS)) append(lines, SIN_BODY);
        boolean heap = referenced.contains(ALLOC) || referenced.contains(FREE);
        if (heap) appendAllocator(lines, referenced, strategy);
        if (referenced.contains(STACK_OVERFLOW)) append(lines, STACK_OVERFLOW_BODY);
        // Every reporter shares the copy loop, so it comes last and only once.
        if (heap || referenced.contains(STACK_OVERFLOW)) append(lines, REPORT_BODY);
        return lines;
    }

    /**
     * The most stack a call to {@code label} uses, its own return address included —
     * which is what the stack bound expects a callee to be charged.
     *
     * <p>Counted by hand from the bodies above, and recounted mechanically from the
     * same text by {@code RuntimeStackTest}, so the two cannot drift apart. It used to
     * be one figure for every helper, sixteen, which {@code __mulhi} exceeded by two.
     */
    public static int stackBytes(String label) {
        return switch (label) {
            case WAITFRAME, GETKEY, MEMCPY, MEMSET, STRLEN, STRCPY, STRCMP, SIN, COS -> 2;
            case SQRT -> 4;
            case SAR, VWRITE, VREAD, VFILL -> 6;
            case SDIV, SMOD -> 8;
            // The bitmap allocator's, the deepest of the three: a saved D, three local
            // words, and the return address of the .rt_bitref it calls.
            case ALLOC, FREE -> 12;
            case MULHI -> 18;
            // Its own frame, the argument it pushes, and the whole of __mulhi.
            case FIXMUL -> 28;
            default -> throw new IllegalArgumentException("no stack figure for " + label);
        };
    }

    /** Whichever of the three allocators this program was found to want. */
    private static void appendAllocator(List<Asm.Line> lines, Set<String> referenced,
                                        HeapStrategy strategy) {
        switch (strategy.kind()) {
            case BUMP -> {
                // No init: the entry stub's one store is the whole of the setup, and
                // a program in this tier has no rt_free to reach the reporter from.
                append(lines, BUMP_ALLOC_BODY);
            }
            case SLAB -> {
                append(lines, SLAB_INIT_BODY);
                append(lines, BITREF_BODY);
                if (referenced.contains(ALLOC)) append(lines, SLAB_ALLOC_BODY);
                if (referenced.contains(FREE)) {
                    append(lines, SLAB_FREE_BODY);
                    append(lines, HEAP_CORRUPT_BODY);
                }
            }
            case BITMAP -> {
                append(lines, HEAP_INIT_BODY);
                append(lines, BITREF_BODY);
                if (referenced.contains(ALLOC)) append(lines, ALLOC_BODY);
                if (referenced.contains(FREE)) {
                    append(lines, FREE_BODY);
                    append(lines, HEAP_CORRUPT_BODY);
                }
            }
        }
    }

    /**
     * Says so, on the display, and stops.
     *
     * <p>Jumped to, never called, and it touches the stack nowhere — no {@code PUSH},
     * no {@code CALL}, no frame. That is the whole design constraint: it runs at the
     * moment the stack has run out, so anything it did to the stack would be the
     * thing it is reporting.
     *
     * <p>Without it the same program reports {@code Invalid opcode: 255}, some
     * distance after the stack has already written over the code that was going to
     * produce the message.
     */
    private static final String STACK_OVERFLOW_BODY = """
            .rt_stack_overflow:
                    MOV B, .rt_so_text
                    JMP .rt_report
            .rt_so_text:
                    DB "STACK OVERFLOW"
                    DB 0
            """;

    /** Handed something that never came from {@code alloc}. */
    private static final String HEAP_CORRUPT_BODY = """
            .rt_heap_corrupt:
                    MOV B, .rt_hc_text
                    JMP .rt_report
            .rt_hc_text:
                    DB "HEAP CORRUPT"
                    DB 0
            """;

    /**
     * Writes the message in {@code B} to the display and stops.
     *
     * <p>Reached by {@code JMP} and never by {@code CALL}, and it touches the stack
     * nowhere. One of its two callers reports that the stack has run out, so anything
     * this did to the stack would be the thing being reported.
     */
    private static final String REPORT_BODY = """
            .rt_report:
                    MOV C, 0x1000           ; the memory-mapped text display
            .rt_report_copy:
                    MOVB AL, [B]
                    CMPB AL, 0
                    JZ .rt_report_stop
                    MOVB [C], AL
                    INC B
                    INC C
                    JMP .rt_report_copy
            .rt_report_stop:
                    HLT
            """;


    /**
     * Video memory is reached one {@code VIDADDR}/{@code VIDDATA} pair at a time —
     * there is no DMA on this machine — and how much one {@code VIDDATA} write moves
     * depends on the mode. In tile mode it stores two bytes, the high half at
     * {@code VIDADDR} and the low at {@code VIDADDR + 1}; in bitmap mode, one.
     *
     * <p>RAM is big-endian and splits a word exactly the same way round, so in tile
     * mode a word loaded from RAM can go straight out to {@code VIDDATA} and lands
     * byte-identically — no shuffling, and two bytes per nine instructions. The
     * running address and source pointer stay in {@code B} and {@code C} and the loop
     * ends on a precomputed limit, so nothing but the port writes touches memory;
     * keeping them in frame slots instead measured 14 instructions a byte, which is
     * also what the obvious hand-written Mona loop costs.
     *
     * <p>The count is in bytes in both modes, which costs a tail case: an odd count in
     * tile mode ends on a single byte, and writing that byte alone would take its
     * neighbour with it. The tail reads the pair back, replaces one half and writes it
     * again, so the byte after the range is left as it was. Paying eight instructions
     * once buys a builtin whose argument means the same thing in both modes.
     */
    private static final String VWRITE_BODY = """
            .rt_vwrite:
                    PUSH D
                    MOV D, SP
                    SUB SP, 2               ; [D-1] where the run stops, then scratch
                    MOV C, [D+5]            ; the source pointer, kept in a register
                    IN 7                    ; VIDMODE
                    CMP A, 1
                    JNZ .vw_byte
                    MOV A, [D+7]
                    AND A, 65534            ; whole pairs only; the odd byte is the tail
                    ADD A, C
                    MOV [D-1], A
            .vw_pair:
                    CMP C, [D-1]
                    JNC .vw_tail
                    MOV A, B
                    OUT 8                   ; VIDADDR
                    MOV A, [C]              ; a whole word, byte order already right
                    OUT 9                   ; VIDDATA, both bytes
                    ADD B, 2
                    ADD C, 2
                    JMP .vw_pair            ; nine instructions, two bytes
            .vw_tail:
                    MOV A, [D+7]
                    AND A, 1
                    JZ .vw_done
                    MOV A, B
                    OUT 8                   ; VIDADDR stays put across the read
                    IN 9                    ; the pair as it stands
                    AND A, 255              ; keep the neighbour's byte
                    MOV [D-1], A
                    MOVB AL, [C]
                    MOVB AH, 0
                    SHL A, 8
                    OR A, [D-1]
                    OUT 9                   ; one byte changed, one preserved
                    JMP .vw_done
            .vw_byte:
                    MOV A, [D+7]
                    ADD A, C
                    MOV [D-1], A
            .vw_bloop:
                    CMP C, [D-1]
                    JNC .vw_done
                    MOV A, B
                    OUT 8
                    MOVB AL, [C]
                    MOVB AH, 0
                    OUT 9                   ; bitmap mode takes the low byte only
                    ADD B, 1
                    ADD C, 1
                    JMP .vw_bloop
            .vw_done:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * The same road in the other direction, and the reason it exists at all: writing
     * {@code VIDADDR} pre-loads {@code VIDDATA} from that address, so video memory
     * reads back. In tile mode a read yields the two bytes at the address as one
     * big-endian word, which is what RAM wants; in bitmap mode, one byte.
     */
    private static final String VREAD_BODY = """
            .rt_vread:
                    PUSH D
                    MOV D, SP
                    SUB SP, 2               ; [D-1] where the run stops
                    MOV C, [D+5]            ; the destination pointer
                    IN 7                    ; VIDMODE
                    CMP A, 1
                    JNZ .vr_byte
                    MOV A, [D+7]
                    AND A, 65534
                    ADD A, C
                    MOV [D-1], A
            .vr_pair:
                    CMP C, [D-1]
                    JNC .vr_tail
                    MOV A, B
                    OUT 8
                    IN 9                    ; both bytes at once
                    MOV [C], A
                    ADD B, 2
                    ADD C, 2
                    JMP .vr_pair
            .vr_tail:
                    MOV A, [D+7]
                    AND A, 1
                    JZ .vr_done
                    MOV A, B
                    OUT 8
                    IN 9
                    SHR A, 8                ; the byte at the address, not its neighbour
                    MOV [D-1], A
                    MOVB AL, [D-1]
                    MOVB [C], AL
                    JMP .vr_done
            .vr_byte:
                    MOV A, [D+7]
                    ADD A, C
                    MOV [D-1], A
            .vr_bloop:
                    CMP C, [D-1]
                    JNC .vr_done
                    MOV A, B
                    OUT 8
                    IN 9
                    MOVB [C], AL
                    ADD B, 1
                    ADD C, 1
                    JMP .vr_bloop
            .vr_done:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * A pattern rather than a copy, so nothing is read from RAM. In tile mode the
     * whole word goes to every pair, which is one map cell — high byte the tile, low
     * byte the colour — so clearing a region of the map to a chosen tile is one call.
     * In bitmap mode only the low half is used, because that is a palette index.
     */
    private static final String VFILL_BODY = """
            .rt_vfill:
                    PUSH D
                    MOV D, SP
                    SUB SP, 2               ; [D-1] where the run stops
                    MOV C, [D+5]            ; the pattern, kept in a register
                    IN 7                    ; VIDMODE
                    CMP A, 1
                    JNZ .vf_byte
                    MOV A, [D+7]
                    AND A, 65534
                    ADD A, B
                    MOV [D-1], A
            .vf_pair:
                    CMP B, [D-1]
                    JNC .vf_tail
                    MOV A, B
                    OUT 8
                    MOV A, C
                    OUT 9                   ; the pattern, both bytes: one map cell
                    ADD B, 2
                    JMP .vf_pair            ; eight instructions, two bytes
            .vf_tail:
                    MOV A, [D+7]
                    AND A, 1
                    JZ .vf_done
                    MOV A, B
                    OUT 8
                    IN 9
                    AND A, 255              ; keep the neighbour
                    MOV [D-1], A
                    MOV A, C
                    AND A, 65280            ; the pattern's high half is the odd byte
                    OR A, [D-1]
                    OUT 9
                    JMP .vf_done
            .vf_byte:
                    MOV A, [D+7]
                    ADD A, B
                    MOV [D-1], A
                    AND C, 255              ; a palette index is one byte
            .vf_bloop:
                    CMP B, [D-1]
                    JNC .vf_done
                    MOV A, B
                    OUT 8
                    MOV A, C
                    OUT 9
                    ADD B, 1
                    JMP .vf_bloop
            .vf_done:
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * Frame pacing without an interrupt handler.
     *
     * <p>The card raises line 2 every 20 ms while the display is on. This polls
     * {@code IRQSTATUS} for that bit rather than letting the CPU vector, so a program
     * needs no handler and never has to enable delivery — but the bit only latches if
     * {@code IRQMASK} has it, because the controller drops a trigger for a masked-out
     * line rather than remembering it. So the bit is OR-ed in on every call: three
     * instructions once per frame, against hanging forever if the program set
     * {@code IRQMASK} for its own reasons and left this line out.
     *
     * <p>Acknowledging is {@code IRQEOI}, which is an <em>XOR</em> and not a clear —
     * writing a bit that is not raised would raise it. That is why the write happens
     * only after the bit has been seen, and why it names line 2 alone and leaves a
     * pending keypad or timer untouched.
     *
     * <p>If the display is off there is no refresh, so waiting would never end. It
     * returns immediately instead.
     */
    private static final String WAITFRAME_BODY = """
            .rt_waitframe:
                    IN 7                    ; VIDMODE
                    CMP A, 0
                    JZ .wf_done             ; no display, so no refresh to wait for
                    IN 0                    ; IRQMASK
                    OR A, 4
                    OUT 0                   ; the card's line, without disturbing others
            .wf_spin:
                    IN 1                    ; IRQSTATUS
                    AND A, 4
                    JZ .wf_spin
                    MOV A, 4
                    OUT 2                   ; IRQEOI: an XOR, so only once it is set
            .wf_done:
                    RET
            """;

    /**
     * The high 16 bits of an unsigned 16&times;16 product.
     *
     * <p>{@code MUL} keeps only the low half, which is the machine's largest
     * arithmetic hole: it is why {@code cube.mona} works at a fixed-point scale of 128
     * rather than 256, and why anything wanting more precision than that has nowhere
     * to go. The product is rebuilt from four byte-wide multiplies, each of which
     * fits 16 bits exactly because both operands are at most 255:
     *
     * <pre>
     *   a * b  =  ah*bh &lt;&lt; 16  +  (al*bh + ah*bl) &lt;&lt; 8  +  al*bl
     * </pre>
     *
     * <p>Two carries have to be tracked and both are easy to lose. The middle sum
     * {@code al*bh + ah*bl} reaches 130,050, so it overflows a word — and one bit of
     * overflow there is worth 256 in the result, not 1. And the low half of the
     * product can carry up into the high half, which is the {@code JNC} at the end.
     * Neither is visible in a small test, which is why {@code MulhiIT} checks it
     * against exact arithmetic over thousands of pairs including every boundary.
     */
    private static final String MULHI_BODY = """
            .rt_mulhi:
                    PUSH D
                    MOV D, SP
                    SUB SP, 14              ; al ah bl bh, the middle sum, its carry, the result
                    MOV [D-1], B            ; argument 0 arrives in a register
                    MOV A, [D-1]
                    AND A, 255
                    MOV [D-1], A            ; al
                    MOV A, B
                    SHR A, 8
                    MOV [D-3], A            ; ah
                    MOV A, [D+5]
                    AND A, 255
                    MOV [D-5], A            ; bl
                    MOV A, [D+5]
                    SHR A, 8
                    MOV [D-7], A            ; bh

                    MOV A, [D-1]
                    MUL [D-7]               ; al*bh
                    MOV C, A
                    MOV A, [D-3]
                    MUL [D-5]               ; ah*bl
                    MOV [D-11], 0
                    ADD A, C                ; the middle sum needs seventeen bits
                    JNC .mh_middle
                    MOV [D-11], 1           ; and the seventeenth is worth 256 above
            .mh_middle:
                    MOV [D-9], A

                    MOV A, [D-3]
                    MUL [D-7]               ; ah*bh
                    MOV [D-13], A
                    MOV A, [D-11]
                    SHL A, 8
                    ADD A, [D-13]
                    MOV [D-13], A
                    MOV A, [D-9]
                    SHR A, 8
                    ADD A, [D-13]
                    MOV [D-13], A

                    MOV A, [D-9]
                    AND A, 255
                    SHL A, 8                ; what the middle sum contributes below
                    MOV C, A
                    MOV A, [D-1]
                    MUL [D-5]               ; al*bl
                    ADD A, C
                    JNC .mh_done            ; the low half can carry into the high
                    MOV A, [D-13]
                    ADD A, 1
                    MOV [D-13], A
            .mh_done:
                    MOV A, [D-13]
                    MOV SP, D
                    POP D
                    RET
            """;

    /**
     * The keypad, read the only way that works.
     *
     * <p>{@code KBDSTATUS} is a bit field: 1 as a key goes down, 2 as it comes up, and
     * 4 added to either when a previous event was never collected — so its values are
     * 1, 2, 5 and 6, and never merely 0 or 1. Reading {@code KBDDATA} is what returns it
     * to 0.
     *
     * <p>That makes the order the whole of it. Testing the status against 1 and leaving
     * early never drains a release: the port latches at 2, the next press reads 5, and
     * the keyboard looks dead from then on. So this collects unconditionally and hands
     * back both halves, {@code (status << 8) | key}, leaving the caller to decide what
     * the event was without being able to skip the part that matters.
     *
     * <p>Zero when nothing is waiting, which is the one case where {@code KBDDATA} must
     * not be read — doing so would clear a request that had not arrived.
     */
    private static final String GETKEY_BODY = """
            .rt_getkey:
                    IN 5                    ; KBDSTATUS
                    CMP A, 0
                    JZ .gk_none             ; nothing waiting: do not touch KBDDATA
                    MOV B, A
                    SHL B, 8                ; the status into the high byte
                    IN 6                    ; KBDDATA, and this is what clears the port
                    AND A, 255
                    OR A, B
                    RET
            .gk_none:
                    MOV A, 0
                    RET
            """;

    private static void append(List<Asm.Line> lines, String body) {
        body.lines().forEach(line -> lines.add(new Asm.Raw(line)));
        lines.add(new Asm.Blank());
    }
}
