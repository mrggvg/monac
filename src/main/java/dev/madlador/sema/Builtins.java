package dev.madlador.sema;

import java.util.Set;

/**
 * Functions the compiler provides without a declaration.
 *
 * <p>The first of them exist because the machine's peripherals — the graphics card,
 * the keypad, the timer, the random generator — live in a port space that is separate
 * from memory and unreachable by any pointer. {@code IN} and {@code OUT} are the only
 * way in, and without a way to spell them from Mona the whole of that hardware is
 * invisible to a program. The rest are C's library staples and the arithmetic this
 * machine makes hard: things every small program otherwise writes by hand, slightly
 * wrong.
 *
 * <p>They are spelled with leading underscores, which is C's own convention for names
 * reserved to the implementation, so a program may declare its own {@code free} or
 * {@code strlen} without colliding with anything, and no header is needed. Eight
 * compile to a single instruction; the rest are calls into hand-written helpers,
 * emitted only when a program uses them.
 */
public final class Builtins {

    /** {@code word __in(word port)} — read an I/O port. */
    public static final String IN = "__in";

    /** {@code void __out(word port, word value)} — write an I/O port. */
    public static final String OUT = "__out";

    /** {@code void __sti()} — allow interrupts to be delivered. */
    public static final String STI = "__sti";

    /** {@code void __cli()} — block interrupts. */
    public static final String CLI = "__cli";

    /**
     * {@code void __setisr(word address)} — install the interrupt handler.
     *
     * <p>There is one vector, at {@code 0x0003}, shared by every device, so a
     * program has exactly one handler and works out who interrupted it by reading
     * IRQSTATUS. The address is stored at run time, so a program may swap handlers.
     */
    public static final String SETISR = "__setisr";

    /**
     * {@code word __alloc(word size)} — allocate from the heap, 0 if there is no
     * room.
     *
     * <p>A program that calls this gets a heap; one that does not, does not. Nothing
     * has to be enabled, because the need is plain from the source and a forgotten
     * flag would be a failure that teaches nothing. Which of the three allocators it
     * gets is decided the same way — see {@link dev.madlador.ir.HeapStrategy}.
     * {@code --heap} and {@code --heap-strategy} exist only to override the two
     * choices for measurement.
     */
    public static final String ALLOC = "__alloc";

    /** {@code void __free(word pointer)} — return a block to the heap. */
    public static final String FREE = "__free";

    /**
     * {@code void __vwrite(word vaddr, word from, word count)} — copy {@code count}
     * bytes of RAM into video memory.
     *
     * <p>There is no DMA on this machine: ports 0 to 10 are all of it, and the only
     * road into the card's 64 KB is one {@code VIDADDR}/{@code VIDDATA} pair at a
     * time. So every program that loads a tile set, a palette or a sprite table
     * writes this same loop, and writes it slightly differently, because how much one
     * {@code VIDDATA} write moves depends on the video mode — two bytes in tile mode,
     * one in bitmap. This reads {@code VIDMODE} and does the right thing, so a byte
     * count means a byte count either way.
     */
    public static final String VWRITE = "__vwrite";

    /**
     * {@code void __vread(word vaddr, word into, word count)} — copy {@code count}
     * bytes of video memory back into RAM.
     *
     * <p>The reverse road exists because writing {@code VIDADDR} pre-loads
     * {@code VIDDATA} from that address. In tile mode the card never looks above
     * {@code 0xA326}, which leaves 23,754 bytes it will not disturb — more than five
     * times the machine's entire RAM, and the only place a 4 KB program has room to
     * spare.
     */
    public static final String VREAD = "__vread";

    /**
     * {@code void __vfill(word vaddr, word value, word count)} — fill {@code count}
     * bytes of video memory with a 16-bit pattern.
     *
     * <p>In bitmap mode every byte becomes the low half of {@code value}, which is a
     * palette index. In tile mode the whole word goes to each pair, which is exactly
     * one map cell — high byte the tile, low byte the colour — so clearing a region
     * of the map to a chosen tile is one call.
     */
    public static final String VFILL = "__vfill";

    /**
     * {@code void __waitframe()} — block until the graphics card's next refresh.
     *
     * <p>The card raises interrupt line 2 every 20 ms while the display is on, and
     * that is the machine's only wall-clock tick: {@code TMRPRELOAD} counts
     * instructions, so its rate follows the simulator's speed setting. This polls
     * {@code IRQSTATUS} rather than taking the interrupt, so it needs no handler, no
     * {@code __setisr} and no {@code __sti} — which is what makes frame pacing
     * available to a program that wants nothing else to do with interrupts.
     *
     * <p>It returns at once if the display is off, because there would be no refresh
     * to wait for and the wait would never end.
     */
    public static final String WAITFRAME = "__waitframe";

    /**
     * {@code word __mulhi(word a, word b)} — the high 16 bits of {@code a * b},
     * unsigned.
     *
     * <p>{@code MUL} is 16&times;16 into 16 and the top half is simply gone, which is
     * the machine's largest arithmetic hole and the reason
     * {@code examples/3-graphics/cube.mona} works at a fixed-point scale of 128 rather than
     * 256. Recovering it takes four byte-wide multiplies and the carries between
     * them — about thirty instructions, far too many to inline and far too fiddly to
     * expect anyone to write twice.
     *
     * <p>With this, {@code (a * b) >> 8} at scale 256 is
     * {@code (__mulhi(a, b) << 8) | (a * b) >> 8} — or for a scale that is a whole
     * word, {@code __mulhi} alone is the answer.
     */
    public static final String MULHI = "__mulhi";

    /**
     * {@code word __ticks()} — the timer's counter, which falls by one per instruction
     * executed.
     *
     * <p>One {@code IN 4}. {@code TMRCOUNTER} decrements once for every instruction the
     * CPU retires, halted ones included, and it is readable — so preloading the timer
     * high and subtracting two reads gives an <em>exact</em> instruction count for the
     * code between them. It is the only self-measurement this machine has, and until
     * now it was reachable only by knowing the port number.
     *
     * <p>It is the timer's counter and not a private one, so a program using timer
     * interrupts is reading the same register that drives them: it wraps at the
     * preload rather than counting freely.
     */
    public static final String TICKS = "__ticks";

    /**
     * {@code word __getkey()} — the keypad's status and its data, together, correctly.
     *
     * <p>Returns {@code (status << 8) | key}, or 0 when nothing is waiting.
     *
     * <p>{@code KBDSTATUS} is a bit field rather than a flag — 1 for a key going down,
     * 2 for one coming up, and 4 added to either when a previous event was never
     * collected — and reading {@code KBDDATA} is what returns it to 0. So the only
     * correct shape is <em>collect first, decide afterwards</em>: code that tests the
     * status against 1 and returns early never drains a release, the port latches at 2,
     * and every later press reads 5. That shipped in {@code snake.mona}.
     *
     * <p>This is the one builtin that encodes a policy rather than exposing a machine
     * primitive, and it earns that by making the trap unskippable:
     * {@code (__getkey() >> 8) & 1} is a key going down, and the low byte is the key.
     */
    public static final String GETKEY = "__getkey";

    /**
     * {@code void __halt()} — stop the machine.
     *
     * <p>One {@code HLT}. Returning from {@code main} does this already; a builtin is
     * for stopping from inside something nested, without threading a result back out.
     * A halted CPU still consumes a tick per step, so an interrupt can restart it.
     */
    public static final String HALT = "__halt";

    /**
     * {@code void __memcpy(word dst, word src, word count)} — copy {@code count} bytes.
     *
     * <p>Unlike C's, overlap is safe: when the destination is above the source the copy
     * runs backwards, which is what {@code memmove} does. The check costs two
     * instructions a call, and the bug it prevents — shifting a buffer up by one and
     * smearing its first byte across the rest — is exactly what a 4 KB program does
     * with its buffers.
     */
    public static final String MEMCPY = "__memcpy";

    /**
     * {@code void __memset(word dst, word value, word count)} — fill {@code count}
     * bytes with the low byte of {@code value}.
     */
    public static final String MEMSET = "__memset";

    /** {@code word __strlen(byte* s)} — how many bytes come before the terminating zero. */
    public static final String STRLEN = "__strlen";

    /** {@code void __strcpy(byte* dst, byte* src)} — copy a string, terminator included. */
    public static final String STRCPY = "__strcpy";

    /**
     * {@code sword __strcmp(byte* a, byte* b)} — negative, zero or positive, as C's.
     *
     * <p>Signed, as C's is, and it matters more here: with a {@code word} result
     * {@code __strcmp(a, b) < 0} could never be true. The value is the difference of
     * the first two bytes that differ, each read unsigned, which is also C's rule.
     */
    public static final String STRCMP = "__strcmp";

    /** {@code word __sqrt(word n)} — the integer square root, rounded down. */
    public static final String SQRT = "__sqrt";

    /**
     * {@code sword __sin(word angle)} — the sine in fixed point: 256 steps to a turn,
     * and a result scaled so that 1.0 is 256.
     *
     * <p>Both scales are chosen for this machine. With 256 steps an angle kept in a
     * byte wraps by itself, and a result of ±256 goes straight into {@link #FIXMUL}.
     * It is a quarter-wave table of 65 words shared with {@link #COS}, emitted once
     * whichever of the two a program uses.
     */
    public static final String SIN = "__sin";

    /** {@code sword __cos(word angle)} — the sine a quarter turn on, at the same scales. */
    public static final String COS = "__cos";

    /**
     * {@code sword __fixmul(sword a, sword b)} — {@code (a * b) >> 8} computed on the
     * whole 32-bit product: the multiply for numbers with eight fractional bits.
     *
     * <p>Signed, because what it multiplies most is {@link #SIN} and {@link #COS}. The
     * result is exactly bits 8 to 23 of the signed product, so it rounds toward
     * negative infinity as an arithmetic shift does, and wraps when the true answer
     * does not fit in a {@code sword}. For unsigned scale-256 arithmetic,
     * {@link #MULHI} gives the recipe.
     */
    public static final String FIXMUL = "__fixmul";

    /**
     * {@code word __random()} — a fresh random word from the hardware.
     *
     * <p>One {@code IN 10}, symmetric with {@link #TICKS}: the generator is a port
     * like any other, and this names it.
     */
    public static final String RANDOM = "__random";

    private Builtins() {
    }

    private static final Set<String> NAMES = Set.of(
            IN, OUT, STI, CLI, SETISR, ALLOC, FREE,
            VWRITE, VREAD, VFILL, WAITFRAME, MULHI, TICKS, GETKEY, HALT,
            MEMCPY, MEMSET, STRLEN, STRCPY, STRCMP, SQRT, SIN, COS, FIXMUL, RANDOM);

    public static boolean isBuiltin(String name) {
        return NAMES.contains(name);
    }

    /** How many arguments a builtin takes. */
    public static int arityOf(String name) {
        return switch (name) {
            case STI, CLI, WAITFRAME, TICKS, GETKEY, HALT, RANDOM -> 0;
            case OUT, MULHI, STRCPY, STRCMP, FIXMUL -> 2;
            case VWRITE, VREAD, VFILL, MEMCPY, MEMSET -> 3;
            default -> 1;
        };
    }

    /** What a builtin evaluates to. */
    public static Type returnTypeOf(String name) {
        return switch (name) {
            case IN, ALLOC, MULHI, TICKS, GETKEY, STRLEN, SQRT, RANDOM -> Type.WORD;
            case STRCMP, SIN, COS, FIXMUL -> Type.SWORD;
            default -> Type.VOID;
        };
    }

    /** Whether a builtin becomes a call to a runtime helper rather than an instruction. */
    public static boolean isHeapBuiltin(String name) {
        return ALLOC.equals(name) || FREE.equals(name);
    }

    /**
     * Whether a builtin is a call into a runtime helper rather than one instruction.
     *
     * <p>The heap pair are calls too, and they are kept separate because they also
     * decide that the program <em>has</em> a heap, which is a whole-program property
     * these have no bearing on.
     */
    public static boolean isRuntimeCall(String name) {
        return switch (name) {
            case VWRITE, VREAD, VFILL, WAITFRAME, MULHI, GETKEY,
                 MEMCPY, MEMSET, STRLEN, STRCPY, STRCMP, SQRT, SIN, COS, FIXMUL -> true;
            default -> false;
        };
    }
}
