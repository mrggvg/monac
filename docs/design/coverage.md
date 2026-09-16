# What of this machine the compiler actually uses

A compiler is only as good as its knowledge of the target, and it is easy to mistake
"everything I have used" for "everything there is". This is the other list: what the
simulator can do that `monac` does not, what that would buy, and what turned out not to
be there at all.

It was produced by reading the simulator's own bundle end to end — the ISA, the CPU,
every peripheral, and the assembler — and comparing it against the compiler's source
and its examples.

**Two kinds of claim appear below, and they are not equally strong.**

| mark | means |
| --- | --- |
| **verified** | a program was compiled and run on the real machine to prove it, and there is a test or a probe in the repo |
| read | taken from the simulator's source, believed but not executed |

Where something is only *read*, treat it as a lead rather than a fact.

---

## The short version

The compiler uses the machine well. It emits **33 of the 47 mnemonics**, every
addressing mode the assembler has, both condition flags, all eleven I/O ports across
its builtins, and both video modes. The gaps are real but narrow, and three of the
things that look like gaps are not capabilities at all.

The five findings worth acting on, in order:

1. **An interrupt that fires inside `__cli()`/`__sti()` is lost permanently** —
   and that is the pattern this compiler's own re-entrancy warning recommends.
   **verified**, with a workaround.
2. **`KBDSTATUS` is a bit field, not a flag.** Polling code that tests it against 1
   latches the port on the first key *release*. This had shipped in `snake.mona`.
   **verified**, fixed.
3. **The tile ROM is CP437**, and its half-blocks and box-drawing glyphs are a free
   scrolling two-colour bitmap that nothing uses.
4. **Byte arithmetic is done entirely in 16-bit registers.** Fourteen byte-width ALU
   instructions are never emitted, and three byte registers are never allocated.
5. **~23 KB of video memory is addressable storage** on a machine with 4 KB of RAM,
   and only graphics uses it.

---

## 1. The instruction set

**33 of 47 mnemonics are emitted.** The eight conditional-jump aliases (`JB`, `JE`,
`JNE`, …) are never used, which is correct — they are spellings, not instructions.

Everything never emitted is a **byte-width ALU operation**:

| never emitted | what it is | worth having? |
| --- | --- | --- |
| `MULB` | `AL = AL * src`, 8-bit | **The best of them.** A `byte * byte` into a `byte` is exactly this, and it would save the two zero-extensions and the truncating mask. Note it is *not* a widening 8×8→16 multiply — `AH` is untouched. |
| `SHLB` `SHRB` `NOTB` | 8-bit shifts and complement | One instruction each: the 16-bit forms need a following `AND r, 255` to truncate, these do not. |
| `ADDB` `SUBB` `INCB` `DECB` | 8-bit arithmetic | One instruction each, but they need the register allocator to model `AH`/`BH`/… as allocatable, which it does not. |
| `ANDB` `ORB` `XORB` | 8-bit bitwise | Marginal — the 16-bit form on a zero-extended byte gives the same answer. |
| `DIVB` | 8-bit divide | **Unusable.** The register form validates its operand with the 16-bit predicate (a simulator bug), so `DIVB AL` raises an illegal instruction and `DIVB A` reads `undefined`. Only the memory and immediate forms work. *read* |
| `PUSHB` `POPB` | one-byte stack traffic | **Should stay unused.** The calling convention is word-based; a one-byte push desynchronises `SP`'s parity and every frame displacement after it. `push` and `pop` are also asymmetric between widths. |

So the single coherent capability left on the table is **native byte arithmetic**, and
the blocker for most of it is sub-register allocation rather than the instruction set.

### Semantics that are not what a compiler writer expects

- **No sign flag and no overflow flag.** Only carry and zero. Every conditional jump is
  unsigned, which is why signed comparisons bias both operands by `XOR 0x8000`.
- **`INC`/`DEC` set carry**, unlike x86. A `CMP` result cannot survive one.
- `MOV`, `MOVB`, `PUSH` and `POP` touch no flags. Everything else that writes a value
  sets both, including `AND`, `OR`, `XOR`, `SHL`, `SHR`, `MUL` and `DIV`.
- **`NOT` is broken at the top of the range.** It computes JavaScript's `~operand` and
  wraps a negative result with `65536 - ((-value) % 65536)`; for `0xFFFF` that yields
  **65536**, and registers are not masked on assignment, so the next store fails with
  `Invalid data value 65536`. **verified** — `~x` where `x` is `0xFFFF` faulted the
  machine at `-O0`. The compiler now emits `XOR reg, 0xFFFF` instead: same one
  instruction, two bytes larger, and it cannot overflow.
- **Shift counts are taken modulo 32, not 16.** `SHL A, 32` is a no-op rather than
  zero, and shifting a value with bit 15 set left by 16 produces 65536 again. C leaves
  this undefined, so it is defensible — but a constant folder that assumes "shift ≥ 16
  gives 0" would disagree with the machine. *read*
- **`MUL` discards the high half** and sets carry on overflow. `__mulhi` rebuilds it.
  The carry is a free "did it overflow" predicate that the language has no way to
  expose.
- **`DIV` is unsigned, floors, and faults on zero.** There is no remainder anywhere in
  the instruction set: `a % b` must be `a - (a / b) * b`.

---

## 2. Registers and flags

| | |
| --- | --- |
| **`SP` is a fifth general-purpose register** | `MOV SP, B`, `ADD SP, C`, `AND SP, 255` all assemble and run. The compiler treats it as the stack pointer only, which is right — but it is not architecturally special. *read* |
| **`IP` and `SR` cannot be named** | No instruction reaches them. A handler can read the interrupted code's `SR` off the stack at `[SP+3]`, which is the only way a program can see it at all. *read* |
| **`BH`, `CH`, `DH` are never emitted** | The compiler uses `AL`/`AH` as scratch and the low halves of allocated registers for byte moves. With only two allocatable colours, the three unused high halves are free storage for `byte` locals. This is the largest register-side gap. |
| **The supervisor bit is dead silicon** | `SR` bit 15 exists in the enum and is read by one method that nothing calls. Nothing sets it; no instruction is privileged. Ignoring it is correct. *read* |
| **`FAULT` is a tombstone** | Set only by the handler that also stops the machine. There is nothing to observe. |

---

## 3. Memory

4,128 bytes total: 4,096 of RAM, then the 32-byte text display at `0x1000`, which is
the top of the address space rather than a window into anything larger.

- **No protection, no segmentation, no paging.** "Memory regions" carry a notification
  callback and nothing else — no permissions, no owner. The stack-into-code collision
  the compiler guards against in software genuinely cannot be caught by the hardware,
  so the generated stack check is doing work nothing else will. *read*
- **Words are big-endian and need no alignment.** Any address is a legal word address.
- **`ORG` is the only assembler directive the compiler never emits**, and correctly:
  it pads forward with zeros, RAM arrives zeroed anyway, and the loaded image would be
  byte-identical. Only the `.asm` text would shrink.

---

## 4. Interrupts — where the traps are

### An interrupt that fires inside a critical section is lost forever

**verified.** `CLI` clears the mask and *then* dispatches a pending request; `STI` sets
the mask and never samples. A device that raises while interrupts are off latches
`interruptInput` but does not vector, `__sti()` does not flush it, and the controller
will not re-raise because its own output is already asserted.

A test that took three timer ticks, ran `__cli()`, spun for twenty-five timer periods,
then ran `__sti()` and spun for a hundred more, counted **zero** further interrupts.

**This matters because it is the pattern this compiler recommends.** The heap
re-entrancy warning tells you to wrap your allocations in `__cli()`/`__sti()`; a
program that does so *and* relies on a timer or keypad handler can wedge.

The workaround is to re-arm the controller by toggling the mask register:

```c
__cli();
// ... the critical section ...
__sti();
__out(0, 0);            // IRQMASK off...
__out(0, mask);         // ...and back on, which re-asserts the line
```

**verified**: the same test with those two lines counted thousands of interrupts
instead of none.

### The rest

- **`IRQEOI` is `status ^= value` then `status |= level`**, not a clear. Acknowledging
  a line that is not raised **sets** it — so write back what you read from `IRQSTATUS`,
  never a constant. **verified** (see `KeypadAckIT`).
- **The keypad is the only level-triggered device.** Read `KBDDATA` before
  acknowledging, or the handler runs twice for one press; never read it and the program
  livelocks. **verified**, three cases.
- **Interrupts raised while masked in `IRQMASK` are dropped, not deferred.** The
  request is gone, not pending. Readers will assume the opposite. *read*
- **`IRQEOI`'s XOR is also a software-interrupt injector**: `__out(2, 8)` raises line 3,
  which no device owns, and dispatches the handler. Lines 3–15 all work. There is only
  one vector, so this is a curiosity rather than a capability. *read*
- **`HLT` plus `__sti()` is a working idle-until-interrupt** and nothing uses it —
  every example spins. The machine charges a tick per halted step anyway, so it saves
  nothing measurable; `__waitframe` polling instead is a deliberate and correct choice.
  *read*
- **`__waitframe` leaves `IRQMASK` bit 2 set** permanently. A program that later calls
  `__sti()` with a handler installed will start taking 50 Hz interrupts it did not ask
  for. *read*

---

## 5. The graphics card

The tile engine itself is now well covered — map, definitions, palette, background,
pixel-granular scroll, sprites, and the free region above `0xA326` are all exercised by
`TileModeIT`. What is not:

- **The tile ROM is IBM CP437**, not "a character set". Tiles `0xB0`–`0xB2` are 25/50/75%
  dither, `0xDB` is solid, `0xDC`–`0xDF` are half-blocks, and `0xB3`–`0xDA` are
  box-drawing. **With half- and quarter-blocks the 128×128 map becomes a scrolling
  two-colour-per-cell bitmap with no tile definitions to upload at all.** Nothing uses
  a tile above the ASCII range. This is the highest-value unused thing on the card.
  *read*
- **Sprite priority is index order and always above the tiles.** Sprite 7 wins; there
  is no way to put a sprite behind the map. *read*
- **Sprites cannot be partially off the left or top.** `x` and `y` are unsigned bytes,
  so a sprite can slide in from the right and bottom only — a real constraint for
  anything that scrolls. *read*
- **`VIDMODE 3` in *bitmap* mode wipes the tile ROM and the palette**, because it
  clears all 64 KB. A `BITMAP → CLEAR → TILE` sequence gives a black screen with blank
  tiles until `VIDMODE 4`. *read*
- **`VIDMODE 4` also turns the display off** and stops the 50 Hz line, which our own
  reference did not say.
- **A `VIDMODE` above 4 does nothing to the card but is still stored**, so `__in(7)`
  returns it. That poisons all four `__v*` runtime helpers, which branch on the mode —
  and `__waitframe` would spin forever on a display that is actually off. Nothing
  validates the argument. *read*
- **Video memory is unreachable while the display is off**, which qualifies the "23 KB
  of slow storage" idea: the display has to be on. *read*
- **In tile mode the host repaints the whole screen on every `VIDDATA` write** — 17×17
  tiles expanded bit by bit — where bitmap mode touches one pixel. Guest instruction
  counts say tile mode is cheaper per byte, and in host wall-clock it is far more
  expensive. Worth knowing before blaming the compiler for a slow simulator. *read*

---

## 6. The keypad

**`KBDSTATUS` is a bit field**: 1 is a key going down, 2 is one coming up, and 4 is
added to either when a previous event was never collected. The values a program can see
are **1, 2, 5 and 6** — never just 0 and 1. **verified**: a probe that never drained
`KBDDATA` observed exactly `1, 6, 5, 6`.

The consequence is a bug that had shipped: `if (__in(5) != 1) return;` never drains on a
key *release*, so the port latches at 2 and every later press reads 5. **`snake.mona`
stopped responding to the keyboard after the first key was let go.** Fixed — drain
first, decide afterwards — and `KeypadAckIT` now pins both the mechanism and the shape
of the code in the example.

Nothing in the repository could catch this, because the test oracle only ever sent
key-downs. It can now send releases too (`Oracle.runWithKeyUp`), and a test shows the
old idiom seeing one key out of three while the correct one sees all three.

Also true and undocumented:

- **Every physical keypress delivers two interrupts**, down and up, with the same
  `KBDDATA`. Interrupt-driven handlers that count presses read double. *read*
- **Named keys are unreachable.** Arrows, Enter, Escape, Backspace, Tab and the
  function keys are all filtered out before they reach the port — the keypad drops
  anything whose name is longer than one character. Space arrives. This is why the
  examples steer with letters. *read*
- **There is no auto-repeat and no queue.** Holding a key gives one event; a second
  event overwrites the first and only sets the overrun bit. *read*
- `KBDDATA` legitimately ranges 0–255, not 0–127: Latin-1 accented characters arrive.
  *read*

---

## 7. The timer, the clock, and the random generator

- **`TMRCOUNTER` (port 4) is read by nothing** — no example, no test, no runtime
  routine. It decrements once per instruction executed, *including while halted*, and
  it is readable. That makes it **a free, exact instruction counter**: preload it high,
  read it before and after a region, subtract. It is the machine's only self-measurement
  facility and the toolchain exposes it only accidentally through generic `__in`. A
  `__ticks()` builtin would be one instruction. *read*
- **Writing `TMRPRELOAD` re-arms the timer immediately** — a free "resync to now" that
  nothing uses. *read*
- **`TMRPRELOAD = 0` disables the timer permanently.** The depletion test is `=== 0`,
  so the counter runs to −1 and never fires again; there is no way back short of a
  reset. *read*
- **Instruction timing is perfectly uniform** — one instruction, one tick, whatever the
  opcode or operand form. The compiler's instruction counts therefore *are* cycle
  counts, which is unusual and worth stating because the optimizer relies on it.
- **The random generator is the host's `Math.random`**: unseeded, not reproducible, no
  range control, uniform over 0–65535. **Mask, do not modulo** — `%` by a
  non-power-of-two is biased. Runs cannot be reproduced, which matters for testing.
  *read*
- **Writing a read-only port faults the CPU.** Ports 1, 4, 5, 6 and 10 are read-only,
  and `__out` to any of them, or to a port above 10, is a hard fault rather than a
  no-op. With a literal port number this is statically detectable and the compiler says
  nothing. *read*

---

## 8. The text display

32 cells, one row, no colour, no cursor, no scrolling, no control register. Newline and
tab render as blanks. It is ordinary RAM and reads back, so it doubles as 32 bytes of
scratch — though every write is visible.

- **A 16-bit store updates only the first character while single-stepping.** The
  simulator's word-store hook has an always-false guard, and the display only re-syncs
  from memory on the Run-mode frame tick. Programs that write it with word stores look
  half-broken under the debugger and correct when run. *read*
- **A word store to the last cell (`0x101F`) crashes the host**, because the bounds
  check tests the base address and not the second byte. *read*

---

## 9. The assembler

Three directives — `DB`, `DW`, `ORG` — and no others. No `EQU`, no `ALIGN`, no macros.

- **There is no expression evaluator at all.** No `label+2`, no `2*8`, no parentheses,
  no location counter. So the two-instruction sequence the compiler emits to reach a
  displaced global is forced, not a missed fold.
- **`[label+n]` does not exist**, confirming the compiler's approach. `[reg±n]` takes a
  signed byte, which is exactly where the 64-slot frame limit comes from.
- **Labels are case-insensitive** and a leading dot means nothing — `.start` is an
  ordinary global label.
- **Negative literals cannot be written at all**, which is why immediates are rendered
  unsigned.
- **`DB` and `DW` read only their first operand** and silently discard the rest.
- **String escapes were the one place the compiler was wrong.** The assembler applies
  its six named escapes without a global flag, so only the *first* `\n` in a string is
  decoded, and it has no case for `\\` at all. The emitter was producing both.
  **verified** — `"a\nb\nc\\d"` came back with a literal backslash and an `n` in the
  middle. Fixed: everything unprintable is now written as `\xNN`, which is the only
  escape the assembler decodes globally, and the size estimate matches the assembler
  exactly.

---

## What this exercise changed

| | |
| --- | --- |
| String literals with two of the same escape, or any backslash, were **silently corrupted** | fixed; `\xNN` only |
| `~x` where `x` is `0xFFFF` **faulted the machine** at `-O0` | fixed; `XOR reg, 0xFFFF` |
| `snake.mona` **stopped responding to the keyboard** after the first key release | fixed; drain then decide |
| The test oracle could not send a key release, hiding that whole class of bug | `Oracle.runWithKeyUp` |

---

## What was done about it

Everything in the first list has since been acted on:

| | |
| --- | --- |
| The `__cli()`/`__sti()` hazard | documented with the `IRQMASK` re-arm, next to the advice that leads into it |
| CP437 block glyphs | documented, and [`blocks.mona`](../../examples/3-graphics/blocks.mona) is a scrolling landscape that uploads no tile data at all |
| Port and video-mode mistakes | compile errors and a warning, for literal arguments |
| `TMRCOUNTER` | `__ticks()`, one `IN 4` |
| The keypad bit field | `__getkey()`, which drains before it decides |

## What is still worth doing

1. **Video memory as general storage.** ~23 KB, two instructions a byte, display-on
   only. The largest unexploited resource on the machine, and nothing uses it that way.
2. **Extending the fuzzer to structs and byte types.** `ProgramFuzzIT` covers locals,
   globals, arrays, pointers, branches, loops and calls; the two type families that have
   actually carried wrong-code bugs here are the two it does not generate.
3. **Byte-width arithmetic** — and the measurement says don't. See
   [roadmap.md](roadmap.md)'s "decided against": 72 uses of `byte` across the whole
   corpus and not one of them arithmetic.

Three things that look like gaps and are not: the supervisor bit (nothing sets it), the
six distinct exception types (there is no vector and no way to resume), and memory
protection (regions are notification wiring). The compiler is right to ignore all three.
