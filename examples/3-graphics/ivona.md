# IVONA, in lights

← [All examples](../README.md) · the program: [ivona.mona](ivona.mona)

[`ivona.mona`](ivona.mona) draws five letters as line strokes and gives each of them a
one-second life. When a letter's second is up it is rubbed out where it stood and
drawn again a little higher or lower, in the next colour of a rainbow. The five clocks
are staggered a fifth of a second apart, so the change rolls along the word rather
than the whole sign blinking at once.

## Nothing is ever cleared

That is the difference from the cube, and the reason this program exists alongside it.
The cube animates the obvious way: clear the screen, redraw everything, repeat. Every
frame pays for every pixel whether or not it moved, which is why it manages less than
a frame a second.

Here the screen is cleared exactly once, before the first letter is drawn. After that
a letter is erased by **drawing its own strokes again in colour 0** — which costs what
drawing it cost — and only the letter whose second is up is touched at all. Four
fifths of the sign is left alone at any moment, and left alone is free.

That works because letters cannot overlap: each sits in a box 24 wide on a 46-pixel
pitch, so rubbing one out can never take a pixel off its neighbour. The 22-pixel gap
is not decoration, it is the precondition.

It also removes the margin for error. A full clear forgives a sloppy redraw; this does
not. If an erase missed one pixel of one stroke, that pixel would stay lit for the rest
of the run and the screen would silently fill up. `IvonaIT.erasingLeavesNothingBehind`
counts the lit pixels after one second and after twenty and requires the same number —
and it earns its place: erasing one row too high turns 614 pixels into 2,220.

## A second is a real second

Which on this machine means the timer interrupt, because the timer is the only clock.
It counts *instructions*, not time, so a second is however many instructions the
simulator runs in one — the speed from the Speed menu. With `TIMER_PRELOAD` at 1000,
`TICKS_PER_SECOND` is the speed in kHz:

| CPU speed | TICKS_PER_SECOND |
| --- | --- |
| 10 kHz | 10 |
| 50 kHz | 50 |

Pick 50 kHz and set the constant to match. A letter's second is then 50,000
instructions, and replacing one costs about 4,200 — erase and draw, two passes over
the same ninety pixels — so five letters a second use about a fifth of the machine.
Ask for more than it can draw and nothing breaks: the deadlines simply arrive faster
than the letters can be redrawn, and the roll falls behind.

The handler does one thing, which is to count. Everything that draws happens in the
main loop, because replacing a letter is thousands of instructions and a handler that
took that long would still be running when the next interrupt arrived. The main loop
then polls, because `HLT` on this machine stops for good rather than idling until the
next interrupt — there is nothing else to spend the waiting on.

## The palette is arithmetic

The graphics card's 256 colours are a **3-3-2 RGB cube**, which means a colour can be
computed instead of looked up:

    index = red * 32 + green * 4 + blue        red, green 0..7   blue 0..3

So 255 is white, 224 red, 28 green, 3 blue, and 252 — red and green full, no blue —
yellow. `colours` is its eight-step ramp, written in exactly that form rather than as eight
magic numbers, because the arithmetic is the point — the compiler folds each one.

It is also how you discover that the cube's `__out(9, 15)`, commented "white" for
rather a long time, is r0 g3 b3: azure.

## Nothing clips, so the arithmetic has to be right

`line` walks a pixel address rather than a pair of coordinates, which is what makes it
fast — and it means a coordinate that wandered off the screen would not fault. It
would wrap into video memory somewhere else and draw a stripe across the picture.

The bound is arithmetic: a letter bobs 26 either side of y=108 and is 39 tall, so the
sign lives in 82..173; the five 24-wide boxes on a 46 pitch from x=13 reach 220.
`IvonaIT.staysOnScreen` runs it long enough to cover the bob and checks the drawn box,
because that argument is only a comment until something runs it.

## What it costs

Nineteen strokes — I is three, V is two, O is eight, N is three, A is three — and 456
pixels for the whole word. Replacing one letter is about 4,200 instructions; the sign
at rest costs nothing at all.

The O is an octagon. A circle would want a square root or a division per point; eight
straight lines read as round at forty pixels tall and cost what any other stroke does.

## Things to try

- Change the word. The `strokes` table and `first` are the only places that know what
  it spells, and a letter is a handful of strokes in a 24 by 40 box. Mind the pitch if you add a sixth:
  five boxes at 46 already reach x=220.
- Give the letters different lifetimes — three seconds for the vowels, one for the
  consonants — by changing what the main loop adds to a letter's `due`.
- Let a letter fade instead of jumping: step it through the ramp every quarter second
  without moving it, so only the colour changes and the erase is not needed at all.
- Drive it from the keypad rather than the timer: a letter is replaced when its key is
  pressed. `interrupt.mona` has the smallest possible version of that handler.
