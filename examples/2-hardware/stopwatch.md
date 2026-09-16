# The stopwatch, in detail

← [All examples](../README.md) · the program: [stopwatch.mona](stopwatch.mona)

[`stopwatch.mona`](stopwatch.mona) counts hours, minutes and seconds on the text
display and resets when you press space. It is the smallest program that needs two
interrupt sources at once, which is what makes it worth walking through.

```
STOPWATCH 00:01:07 SPACE=RESET
```

## Running it

1. Compile: `monac examples/2-hardware/stopwatch.mona -o stopwatch.asm`
2. Paste the assembly into the simulator and press **Assemble**.
3. Pick **Speed → 10 kHz**. The default is 4 Hz, which is for stepping through code
   rather than running it — at 4 Hz a second of stopwatch time takes forty minutes.
4. **Run**. Click the keypad, then press space to reset.

## Three facts about the machine

**There is one interrupt vector.** It sits at address `0x0003`, and every device
shares it. So a program has one handler and asks `IRQSTATUS` who interrupted it:

```c
word pending = __in(IRQSTATUS);         // port 1: who is asking
if (pending & KEYPAD) { /* bit 0 */ }
if (pending & TIMER)  { /* bit 1 */ }
__out(IRQEOI, pending);                 // port 2: acknowledge exactly what you handled
```

Two conditions both have to hold before anything is delivered: the device's bit must
be set in `IRQMASK`, and interrupts must be unmasked globally with `__sti()`. Forget
either and the handler silently never runs. Forget the `IRQEOI` write and it runs
forever, because `IRET` returns to a still-raised request.

**The timer counts instructions, not time.** Writing `TMRPRELOAD` loads a counter that
falls by one per instruction and raises IRQ 1 when it reaches zero, then reloads.
There is no other clock — every instruction costs exactly one tick, so instructions
*are* the unit of time. A second is however many the simulator executes per second,
which is the speed you picked from the menu:

| CPU speed | `TIMER_PRELOAD` | `TICKS_PER_SECOND` |
| --- | --- | --- |
| 1 kHz | 1000 | 1 |
| 5 kHz | 1000 | 5 |
| 10 kHz | 1000 | 10 |
| 20 kHz | 1000 | 20 |
| 50 kHz | 1000 | 50 |

Leave the preload at 1000 and `TICKS_PER_SECOND` is just the speed in kHz.

**The display is memory.** Thirty-two characters live at `0x1000`, one byte each.
Writing to them is an ordinary store through a pointer — there is no driver, no port,
and nothing to initialise:

```c
byte* const SCREEN = 4096;  // 0x1000
SCREEN[0] = 'S';
```

Declared `const` with a constant address, the pointer is itself a constant, so that
store goes straight to address 4096 with nothing loaded first.

## Two ideas in the program worth stealing

**The handler is short on purpose.** It counts, and it sets a flag. It does not draw.
Everything inside a handler runs with the rest of the program suspended, so the less
that happens there the better — and drawing eight characters means eight stores, two
divisions and two remainders.

```c
if (pending & TIMER) {
    if (++ticks >= TICKS_PER_SECOND) {
        ticks = 0;
        tickSecond();
        dirty = 1;              // main will notice
    }
}
```

**The flag is cleared before the redraw, not after.** That ordering is the whole
correctness argument for a program with concurrency in it:

```c
while (1) {
    if (dirty != 0) {
        dirty = 0;
        render();
    }
}
```

An interrupt that lands halfway through `render()` sets `dirty` again, so the next
pass redraws. Clearing it afterwards would throw that update away and leave half of
one time and half of the next on screen — for one second, until the next tick papered
over it, which is exactly the kind of bug that is impossible to catch by watching.

## Two digits

```c
void putNumber(word at, word value) {
    SCREEN[at]     = '0' + value / 10 % 10;
    SCREEN[at + 1] = '0' + value % 10;
}
```

The second `% 10` is doing two jobs. It keeps a three-digit value inside its two
columns instead of pushing a character sideways, and it is what lets the compiler see
that the result fits in a byte. Arithmetic promotes to sixteen bits whether or not the
result needs them, so `SCREEN[i] = something` is a narrowing assignment — but `'0'` is
48 and a remainder by ten is at most 9, so 57 is as large as this gets and the
compiler says nothing. Write `'0' + value / 10` instead and it warns, correctly: a
word divided by ten can still be 6553.

## What the compiler makes of it

```
-O0   312 instructions, 1101 bytes
-O1   173 instructions,  596 bytes      -44.6%, and roughly half the RAM
```

`--dump-asm` is worth a look. `tickSecond`, which carries the whole rollover, comes
out with no prologue and no epilogue at all:

```asm
m_tickSecond:
        MOV   B, [g_seconds]
        INC   B
        MOV   [g_seconds], B
        ...
        MOV   [g_seconds], 0          ; an immediate straight into memory
        MOV   B, [g_minutes]
        ...
.L34_epi_tickSecond:
        RET
```

No `PUSH D`, no `MOV D, SP`, no `MOV SP, D`, no `POP D` — the compiler works out how
far it has pushed the stack at each instruction and addresses the frame from `SP`
directly, so a function that needs no frame pays nothing for one. `MOV [g_seconds], 0`
writes an immediate to memory without a register in between, and elsewhere in the
handler a global is compared straight out of memory, which on this machine costs the
same single tick as comparing a register. That is also why an allocator with only two
registers to hand out still does well. Both are covered in
[../../docs/design/optimization.md](../../docs/design/optimization.md).

The output is not perfect, and it is more useful to say where. In `onIrq`, `IN 6` is
followed by `MOV B, A` and `CMP B, 32`: the key code travels through `B` only to be
compared, where `CMP A, 32` would have done. And each early `return` in `tickSecond`
is a branch over a jump to the epilogue rather than one branch to it: the peephole
that folds that shape gives up when a label sits between the two, even one nothing
jumps to. This paragraph used to point at a reload
in `tickSecond` — `MOV [g_seconds], B` followed by `MOV B, [g_seconds]`, when the
peephole tracked only `A` — and at a dead load the dead-move pass left because it
assumed a call could read any register. Both are gone.

## Things to try

- Add hundredths. `TICKS_PER_SECOND` is already a subdivision of a second; a second
  counter for tenths and two more display columns is about ten lines.
- Make space **start and stop** instead of resetting, and reset on `R`. The handler
  already has the key in hand.
- Count *down* from a time you set with the number keys, and blink the display when it
  reaches zero. Blinking needs a second timer subdivision, which the tick counter
  already gives you.
- Drop `dirty` and call `render()` from inside the handler instead. It works — until
  you lower `TIMER_PRELOAD` far enough that drawing takes longer than the gap between
  ticks. Find that point, and you have measured your own handler in clock cycles.
