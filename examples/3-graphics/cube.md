# The cube, in detail

← [All examples](../README.md) · the program: [cube.mona](cube.mona)

[`cube.mona`](cube.mona) draws a wireframe cube in bitmap mode and spins it. The
corners are given in normalized coordinates — every one is at -1 or +1 on each axis —
rotated about the vertical axis, projected with perspective, and joined by twelve
lines.

```
        ###################################
        ###............................####
        #..##........................###..#
        #....#########################....#
        #.....#....................#......#
        #.....#....................#......#      angle 0: the near face
        #.....#....................#......#      outside, the far face
        #.....#....................#......#      inside and smaller
        #....########################.....#
        #..###.......................##...#
        ####...........................##.#
        ###################################
```

It is the first program here whose design is dictated by what the machine cannot do,
which is what makes it worth reading.

## No floating point, so: fixed point

Every value is stored multiplied by 128, and 1.0 is written as 128. Multiplying two
of them gives something scaled by 128 × 128, so every product shifts back down by 7.

The scale is 128 rather than the more natural 256 because of a single hardware fact:
**`MUL` is 16 bits by 16 bits into 16 bits.** The top half of the product is not
stored anywhere — there is no high-word register, no widening form. A rotation
computes `x·cos + z·sin`, whose largest possible value is √2, so:

| scale | widest intermediate | fits in a signed word? |
| --- | --- | --- |
| 128 | 23,170 | yes, with 9,597 to spare |
| 256 | 92,680 | no — every corner wraps |

That is the whole reason the cube is drawn at 7 fractional bits and not 8.

## No sine, so: a table and some symmetry

Seventeen entries cover a quarter turn — `sin(k · 5.625°) · 128` for k = 0..16 — and
the other three quadrants are mirror images:

```c
sword sine(word step) {
    word s = step & 63;
    if (s < 16) return quarter[s];
    if (s < 32) return quarter[32 - s];
    if (s < 48) return -quarter[s - 32];
    return -quarter[64 - s];
}

sword cosine(word step) { return sine(step + 16); }
```

Cosine is free: it is sine a quarter turn ahead. Sixty-four steps make a revolution,
so one frame turns the cube 5.625°.

## The perspective is the illusion

```c
sword depth = z + DEPTH;                    // DEPTH is 4.0, so 512
sx[i] = CENTRE + x * FOCAL / depth;
sy[i] = CENTRE - y * FOCAL / depth;
```

Dividing by depth is the entire three-dimensional effect. Drop it — project
orthographically by ignoring z — and a cube seen face-on is a square, and a cube seen
at an angle is a flat hexagon. It is the near face being *bigger* that the eye reads
as depth. `FOCAL` is 140 because that is the largest value for which `x * FOCAL`
still fits in a signed word at the worst corner; larger would need the same care as
the rotation.

## What it costs

A frame, measured on the machine by stopping after each stage:

| | |
| --- | --- |
| clear the screen | **3** instructions |
| rotate and project 8 corners | 1,514 |
| draw 12 lines, 676 pixels | 12,391 |
| **a frame** | **13,908** |

Two things stand out. The screen clear is *three instructions* for 65,536 pixels,
because `VIDMODE 3` means clear and the card does it — writing those pixels by hand
would be 131,072 instructions, nine frames' worth. And drawing is 89% of
everything: about 18 instructions per pixel, of which the two `OUT`s are four.

It was 34 instructions a pixel until the inner loop was rewritten around what this
machine actually charges for — the four changes are listed in the comment on `line`,
and the rewrite draws pixel-for-pixel the identical picture, which is checked by
running both versions over a grid of endpoints and comparing what they plot.

Since every instruction is one clock tick, the frame rate follows directly from the
speed in the menu:

| CPU speed | seconds per frame | a full revolution |
| --- | --- | --- |
| 10 kHz | 1.4 | 1m 29s |
| 20 kHz | 0.70 | 45s |
| 50 kHz | 0.28 | 18s |

So: it works, and it is still slow — a good deal less slow than it was. That is
mostly the machine's answer rather than the compiler's: hand-written assembly would
draw the same 676 pixels through the same two `OUT`s, and those two are already four
of the eighteen instructions a pixel costs.

## Two things the compiler taught here

**It does not compile at `-O0`.**

```
error: function 'rotate' needs 67 frame slots, but at most 64 can be addressed
    note: indirect operands have a signed-byte displacement
```

Without optimization every intermediate value gets its own frame slot, and `rotate`
has 67 of them; the displacement field is a signed byte, so 64 is the ceiling. Local
promotion and register allocation are what make this program exist at all. It is the
only example here that needs them.

**A port number in a variable costs three instructions per write.** `OUT` takes its
port as an immediate or from a register and nothing else, so a port held in a global
has to be loaded into one — and with every register busy, the compiler frees one by
pushing it. When the ports were globals, writing literals in the inner loop instead,
and inlining the two-line `plot` helper, took a frame from 38,618 instructions to
27,790 — the first of the four changes now listed in the comment on `line`. They are
an `enum` now, and an enumerator is the immediate, so `__out(VIDADDR, addr)` is
`OUT 8` and nobody has to write the 8. A `const word VIDADDR = 8` does the same — as
of the rewrite of these examples, which found that a `const` was still being loaded.

## Things to try

- Tumble it instead: rotate y and z in `rotate` rather than x and z. One line.
- Rotate about two axes at once, at different rates. The sine table already has
  everything needed; it costs four more multiplies per corner.
- Draw the back edges in a dimmer colour. Which edges are at the back is decided by
  the sign of the rotated z, which `rotate` already computes and throws away.
- Make it a different solid. Only the corner tables `cx`, `cy`, `cz` and the edge
  tables `ea`, `eb` know the shape — a tetrahedron is four corners and six edges, and
  then the loop bounds in `rotate` and `draw`.
- Chase the last few instructions per pixel. The floor is four — two `OUT`s that can
  only send `A`, to an address register that does not auto-advance — and the loop is
  at about eighteen. `e = e - dy` is one instruction now; what is left is
  Bresenham's own bookkeeping — two compares, their branches, the address steps — so
  the next win has to come from the algorithm: drawing a line from both ends at once
  plots two pixels for each update of the error.
