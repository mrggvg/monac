# Roadmap

Where this got to, and what is left. Every "done" line below is covered by tests that
run on the real simulator; see [../building.md](../building.md) for how to run them.

## Done

| | |
| --- | --- |
| **Front end** | lexer, recursive descent + Pratt expressions, error recovery with positions |
| **Types** | `void`, `byte`, `word`, `sbyte`, `sword`, pointers, arrays of any rank, `struct`, `union`, `enum`, `sizeof`, and `const` at every level C allows it |
| **Statements** | `if`/`else`, `while`, `do`/`while`, `for`, `switch`, `break`, `continue`, `goto`, `return`, blocks |
| **Operators** | C's, at C's precedence — `++`/`--`, casts, `?:` and the comma operator included |
| **Functions** | parameters, forward references, checked prototypes, recursion and mutual recursion, function pointers |
| **Data** | globals and `static` locals with initializer lists — numbers, strings, and the addresses of strings, globals and functions — arrays of structs, recursive structs |
| **Heap** | `__alloc`/`__free`, sized at startup and inferred from the source; three allocators — bump, fixed cells, bitmap — chosen per program, `HEAP CORRUPT` on a bad pointer, a warning when a handler and the program can allocate at once |
| **Hardware** | `__in`/`__out` for the graphics card, keypad, timer and RNG; `__sti`/`__cli`/`__setisr` for interrupts |
| **Optimizer** | local promotion, constant folding, strength reduction, copy propagation, dead code, branch simplification, loop-invariant code motion, whole-module reachability |
| **Library** | `__memcpy` (overlap-safe), `__memset`, `__strlen`, `__strcpy`, `__strcmp`, `__sqrt`, `__sin`/`__cos` in fixed point, `__fixmul`, `__random` |
| **Convention** | first argument in `B`, result in `A`; 15.3% off what actually executes |
| **Back end** | Chaitin-Briggs colouring over `B` and `C` with Briggs and George coalescing, frame-pointer elimination, peephole, load folding, dead-move removal |
| **Memory** | the stack requirement computed from the call graph, the heap sized around it, and a run-time guard that reports `STACK OVERFLOW` on the display |
| **Testing** | the simulator's own assembler and CPU driven headlessly, so tests assert against the real machine; the heap contract run under all three allocators, and the image-size estimate checked against the assembler; whole programs fuzzed against a reference interpreter at both levels, structs and every integer width included |

Measured against the course book, every chapter is reachable: numbers and assembly,
memory, conditionals, loops, the stack and functions, arrays and strings, keyboard
polling, interrupts, and graphics.

## Done since the road toward C

- **The fuzzer covers structs and every integer width,** plus casts, `?:`, compound
  assignment and `++`, at `-O0` and `-O1`. Its first 9,000 programs found three bugs:
  a signed compare that biased in `B` whatever was there, a byte store with no
  register to travel in, and a dead store landing in a stack slot already handed to a
  loop counter. Breaking `sbyte` sign-extension on purpose is caught at once.
- **The copy after `e = e - dy` is coalesced.** It was never the non-SSA limit this
  file blamed: copy propagation kept the temporary alive past the copy, and the
  coalescer's safety test counted neighbours it should not have. A `cube.mona` frame
  went from 15,448 steps to 13,958; the corpus from 890,032 steps to 878,850.
- **Loop-invariant code motion,** with signed comparisons now biased in the IR so the
  bias of a side that never changes is hoisted too. 4.4% of the corpus's steps, 7% of
  the heaviest loop — held back, in a loop with no call, to what fits two registers.
- **A better peephole.** It tracked what `A` mirrored and only `A`; now `B` and `C`
  carry their own facts, and two registers mirroring one location count as equal, so
  `MOV B, A` then `MOV A, B` loses its second half. 1.65% of the corpus's
  instructions and 1.2% of its steps, 7.9% of `heap_ring`. It also stopped
  remembering `[A]` after `MOV A, [A]`, which had deleted nothing only by luck.
- **`&local` without a frame pointer.** This file expected it to cost an instruction
  per address taken. It costs none: the selector already follows `MOV A, D` with the
  `SUB` of the local's offset, and `MOV A, SP` takes the displacement from `SP` into
  that same `SUB`. The 30 functions that kept a frame pointer for it lose three
  instructions each, 0.84% of the corpus. No compiled function keeps one now — only
  two of the hand-written runtime helpers.
- **A `const` with a constant value is read as that value.** The analyzer knew it all
  along — it could size an array with one — but every read still loaded it from
  memory, so a port or a screen address named with `const` cost a load where an
  `enum` cost nothing. Now both are immediates, and a `const` pointer with a constant
  address folds too. Found while rewriting the examples around the language's newer
  features.
- **A compound assignment evaluates its target once.** The parser made `++t` and
  `t op= v` into `t = t op v` sharing one node, and lowering computed that node's
  address twice, so `++buf[pos++]` stepped `pos` twice and `a[next()] += 1` called
  `next` twice. The address is now computed once and shared with the read, as `t++`
  always did — which also made `heap_ring` 4% faster. An assignment now yields what
  its target holds afterwards, so `++b` on a byte at 255 is 0 rather than 256, and
  `++b` no longer warns about truncation. Found by a review of this work.
- **Every integer and character constant C has.** Hexadecimal and binary were there;
  octal was not, and `017` was quietly seventeen where C makes it fifteen. Now a
  leading `0` means octal, the suffixes `u`, `l` and `ll` are accepted in any order C
  allows, C23's `'` digit separators work, and the escapes are C's whole set —
  octal bytes, `\a`, `\f`, `\v`, `\?`, and `\x` with any number of digits.
- **`sbyte x = -1;` is silent.** A negative constant is a large 16-bit pattern, and the
  range check compared that pattern with 255; -128..-1 now fit a signed byte, as in C.

## Not done, roughly in order of what they would buy

**A second argument in a register.** The first one is done. A second would leave a
two-argument function with no allocatable register on entry, since `A` is the scratch
and `D` the frame pointer, so it wants the third register first — which was itself
measured and declined. Two negatives that only turn positive together.

## Toward C

The goal changed from a language just sufficient for the machine to one as close to C
as the machine allows, so several things this file used to decline — `do`/`while`,
`?:`, unions, multi-dimensional arrays — are now built or planned. Done:

- `++`/`--`, prefix and postfix; casts; `?:`; the comma operator; `do`/`while`; and
  prototypes, checked against their definitions.
- Ten builtins: `__memcpy`, `__memset`, `__strlen`, `__strcpy`, `__strcmp`, `__sqrt`,
  `__sin` and `__cos` in fixed point, `__fixmul` and `__random`. Still `__`-prefixed
  and still needing no header: `__` is C's own convention for reserved names, and it
  already does the job a header would. Two differ from the plan on purpose:
  `__strcmp` and `__fixmul` are signed, because a `word` result would make
  `__strcmp(a, b) < 0` never true, and what `__fixmul` multiplies most is a sine.
- `enum`, `const` and `static` locals. Enum constants are resolved by the analyzer and
  recorded per node, the way `sizeof` always was, so the constant folder still needs
  no scope. A `const` scalar with a constant initializer is a constant too — C23's
  `constexpr`, and C++'s `const` — so array lengths and case labels take any constant
  expression, and `word a[N]` works. `const` is tracked per pointer level and costs
  no code. Constant folding now divides, shifts and compares signed operands as
  signed; it treated everything as unsigned before.
- Initializer lists, by C's rules — nested braces, brace elision, a string for a byte
  array, `[]` counted from the list, zero for the rest — for locals, statics and
  globals alike. A global's may hold the address of a string, a global or a function,
  so a table of strings is data in the image. Unions, laid out by the struct pass with
  every offset 0. Arrays of any rank, and array parameters written with their
  lengths. Not done: designated initializers, and `&table[1]` as an address constant,
  which needs label arithmetic the assembler does not have.
- Function pointers, declared as C declares them, called through `CALL [A]`, and
  checked against their signatures. The plan expected them to cost two guarantees —
  the stack bound and the heap re-entrancy warning, neither of which could see through
  an indirect call. They cost neither: only a function whose address was taken can be
  in a pointer, so an indirect call is given an edge to every such function, and both
  analyses carry on over that graph. What it costs instead is caution, since every
  address-taken function now counts as a possible interrupt handler.
- `goto`, within a function, out of blocks or within one, and never forward past a
  declaration.

That is the end of the road toward C. Floating point was the last item on it, and it
is declined below.

## Decided against, with reasons

- **Byte-width arithmetic, and allocating the high halves.** The machine has fourteen
  byte ALU instructions the compiler never emits and eight byte registers it never
  allocates, which looks like the largest gap on the box. It measures as the smallest:
  across every example and corpus program there are 72 uses of `byte` and **not one does
  byte arithmetic** — they are `byte*` buffers, string pointers and byte-sized storage.
  It would optimise code this project does not contain. The register half is worse than
  it looks: `D` is the frame pointer, so `DH` is unavailable, and `AH`/`AL` are the
  reserved scratch — the real proposal is "allocate `BH` and `CH`", and the allocator has
  no notion of register width at all (`Ir.VReg` is an int, `GraphColoring` a flat
  two-colour list). That is a structural change to the IR, the interference model, the
  colouring and every one of the twenty `homeOf` consumers in the selector. If byte
  arithmetic ever becomes common in real programs, the cheap version is a
  post-selection peephole over the stereotyped shapes, not an IR change.
- **A third allocatable register.** Freeing `D` cuts spilled values from 139 to 86 and
  saves **8 instructions, 0.33%**, with five programs getting *worse*; freeing `A` as
  well reaches 1.8% and is unsound without modelling every use of the accumulator.
  ALU instructions take a memory operand at the same one tick, so a spilled value that
  is only read costs nothing — the register pressure was never the problem. Twenty
  lines of operand reordering in the selector beat it three times over.
- **Structs by value.** The convention pushes one word per argument and returns one in
  `A`. Widening it means variable-width arguments and a hidden out-pointer, in order
  to encourage copying structs across calls on a machine with 4 KB of RAM. Pointers
  say what was meant, and the compiler suggests one.
- **`typedef`.** Every type in Mona begins with a keyword and no expression does, so
  `(TYPE)` versus `(expr)` is decided by one token with no symbol table — in a cast, in
  `sizeof`, and in telling a declaration from an expression statement. A typedef name
  would break all three at once. Mona keeps its own type names instead.
- **A preprocessor.** `enum` and `const` cover what small C programs use `#define`
  for, with type checking instead of text substitution.
- **Floating point.** There is no FPU, so a `float` would be a software runtime; a
  float is four bytes on a machine whose every value and register is two, which
  reaches the calling convention and the register allocator; and a credible IEEE-754
  runtime is 1–2 KB of a 4 KB machine. Fixed point does the job here — `__fixmul`,
  `__sin` and `__cos` at scale 256 — and `cube.mona` is what it looks like.
- **`abs`, `min`, `max` and `clamp` as builtins.** With `?:` each is one line, and a
  builtin would have to fix its signedness in its signature, where user code gets it
  right from the operand types.
- **Separate compilation.** One file, one image, 4 KB. A linker would be a lot of
  machinery for a program that has to fit in four kilobytes anyway.
- **Inlining the allocator's fast path.** 11.6% on a loop whose entire body is an
  allocation, 1.0% on a real program, and five bytes *smaller* — the whole saving is
  the `CALL` and the `RET`. Declined for the machinery it would need in the
  instruction selector, not for the numbers: expanding a call inline needs a unique
  label per site and has to stay correct through frame-pointer elimination and the
  peephole. The first thing to revisit if the selector ever learns to expand a call
  for another reason.
- **Rounding mixed allocation sizes into one cell**, so more programs reach the slab
  allocator. Break-even needs the average waste under about 0.8 bytes an object:
  four-and-six mixed sits exactly on the line, four-and-eight loses. The cliff to the
  general allocator is roughly where it should be.
- **Making the allocator re-entrant.** `CLI`/`STI` inside it would cost every program
  two instructions a call, and would silently re-enable interrupts for one that had
  disabled them on purpose. The compiler warns instead, which costs nothing at run
  time and is exact — the machine masks interrupts inside a handler, so it takes
  allocation on both sides to collide.

## How to decide the next one

The pattern that has worked here is to measure before building, and to write the
negative results down — including the ones that contradict something already written
here. [optimization.md](optimization.md) records the third register, the frame pointer
kept in memory, and inlining the allocator, precisely so none gets chased again
without new evidence; it also records a claim that turned out to be wrong, about which
half of an allocator change the win came from. `monac <file> --stats` reports instructions and
image size, and the whole corpus is under `src/test/resources/e2e/`.
