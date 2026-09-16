# Worked examples

Each folder is a step up from the last, so they are worth reading in this order.
Every program compiles with `monac`, runs on the simulator unchanged, and is run by the
test suite — an example that stops working fails the build.

## 1-basics — the language

| | | instr | bytes | stack |
| --- | --- | --- | --- | --- |
| [simple.mona](1-basics/simple.mona) | a function, a call, a result in `A` | 13 | 35 | 10 |
| [sandbox.mona](1-basics/sandbox.mona) | Euclid's algorithm, for reading the optimizer's output | 27 | 79 | 10 |
| [complex.mona](1-basics/complex.mona) | an enum, a `switch`, `?:`, signed division, recursion | 187 | 630 | recursive |

## 2-hardware — ports, interrupts and the display

| | | instr | bytes | stack |
| --- | --- | --- | --- | --- |
| [graphics.mona](2-hardware/graphics.mona) | the graphics card through `__out`, every value a constant | 25 | 80 | 4 |
| [interrupt.mona](2-hardware/interrupt.mona) | one keyboard interrupt, as small as it goes | 52 | 147 | 20 |
| **[stopwatch.mona](2-hardware/stopwatch.mona)** | **two interrupt sources and the text display** — [walkthrough](2-hardware/stopwatch.md) | **173** | **596** | **34** |

## 3-graphics — pictures that move

| | | instr | bytes | stack |
| --- | --- | --- | --- | --- |
| [blocks.mona](3-graphics/blocks.mona) | a scrolling landscape from four glyphs of the tile ROM | 208 | 662 | 18 |
| **[cube.mona](3-graphics/cube.mona)** | **a rotating 3D wireframe, in fixed point** — [walkthrough](3-graphics/cube.md) | **454** | **1480** | **18** |
| **[ivona.mona](3-graphics/ivona.mona)** | **a name in lights: one-second letters, on the timer** — [walkthrough](3-graphics/ivona.md) | **507** | **1641** | **56** |

## 4-data — structures in 4 KB

| | | instr | bytes | stack |
| --- | --- | --- | --- | --- |
| **[tree.mona](4-data/tree.mona)** | **a binary search tree: a recursive struct, a node pool, recursion** — [walkthrough](4-data/tree.md) | **241** | **750** | **recursive** |
| [heap.mona](4-data/heap.mona) | `__alloc`, `__free` and a linked list: the one example that allocates | 220 | 753 | 24 |

## 5-games — all of it at once

| | | instr | bytes | stack |
| --- | --- | --- | --- | --- |
| [snake.mona](5-games/snake.mona) | a whole game: tiles, sprites, a ring buffer, the keypad | 947 | 3104 | 32 |

## Running one

```bash
monac examples/2-hardware/stopwatch.mona -o stopwatch.asm
```

Paste the assembly into the simulator, press **Assemble**, pick a speed, and **Run**. The
programs that keep time — `stopwatch` and `ivona` — have a `TICKS_PER_SECOND` constant
that must match the speed you pick: 10 for 10 kHz, 50 for 50 kHz. `snake` and `blocks`
pace themselves on the graphics card's refresh instead, so any speed of 50 kHz or more
will do.

## The numbers

All three numbers come from `monac <file> --stats`, and they answer different
questions. The byte column is what has to fit in 4096 bytes, and it is exact —
`ImageSizeIT` checks every one of these programs against the simulator's own
assembler. The instruction column counts every line of the image, data included, so a
table written as an initializer list shows up in it once per entry: it measures size,
not speed. Speed is the number of instructions a program *executes*, since every one
costs exactly one clock tick, and the sections below measure that where it matters.
The stack column is how much of what is left the program will want back — computed
from the call graph, except where recursion means there is no such number and the
program is given a floor and a run-time check instead.

## How they are written

The examples double as the house style, so they use what the language has:

- **Ports and fixed numbers are `enum` or `const`.** Both are constants:
  `__out(VIDADDR, x)` compiles to `OUT 8`, and `i < LETTERS` compares with an
  immediate. A port held in an ordinary variable costs a load into a register first,
  because `OUT` takes nothing else.
- **Tables are initializer lists.** A sine table, a cube's corners, a font of line
  strokes: in the image as data, with no code run at startup to build them.
- **Memory is fixed arrays, not the heap.** A program that knows how much it needs —
  ten tree nodes, a snake at most half a board long — declares it, and the compiler
  reports the memory before the program runs. There is no allocator in the image and
  nothing to fail at run time. `heap.mona` is the one example that allocates, and
  says when that is worth it.
- **`byte* const SCREEN = 4096`** names the text display. A `const` pointer with a
  constant address folds like any other constant, so `SCREEN[i] = c` stores to a
  fixed address plus an index with nothing loaded first.
- **`++`, `+=`, `?:` and `for`** where C would use them, and a pointer walked through
  an array of structs where an index would cost a multiply.

Rewriting them this way took cube from 2,312 bytes to 1,480 and ivona from 3,018 to
1,641, and found that a `const` was still being loaded from memory on every read —
which the compiler now folds, as it always did for an `enum`.

