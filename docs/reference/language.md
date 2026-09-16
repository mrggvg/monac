# Mona, in full

Everything the language has, with a working example of each, and then the builtins.

Nothing here is written from memory. Every claim is compiled and executed by four
programs under `src/test/resources/e2e/` — [`syntax_expressions.mona`](../../src/test/resources/e2e/syntax_expressions.mona),
[`syntax_data.mona`](../../src/test/resources/e2e/syntax_data.mona),
[`syntax_control.mona`](../../src/test/resources/e2e/syntax_control.mona) and
[`syntax_structs.mona`](../../src/test/resources/e2e/syntax_structs.mona) — which return 0
only if all of it holds, at both `-O0` and `-O1`, on the real machine. If this file
ever drifts away from the compiler, those fail. Writing it this way found three real
bugs — `sbyte` never sign-extended on load, an array that is a struct member indexed
off the wrong base, and `word a[3] = 7;` accepted and then quietly dropped — which is
the argument for checking a reference against the compiler rather than composing one
from memory.

[Mona.g4](Mona.g4) is the grammar. This is the guide.

---

## A whole program

```c
// Every program is one file. main returns a word, and that word is left in
// register A when the machine halts.
word gcd(word a, word b) {
    while (b != 0) {
        word t = b;
        b = a % b;
        a = t;
    }
    return a;
}

word main() {
    return gcd(1071, 462);      // 21
}
```

```bash
monac program.mona -o program.asm
```

There is no `#include`, no linker and no library. One file becomes one image, and the
image has 4 KB for code, data, heap and stack together.

The flags worth knowing:

| | |
| --- | --- |
| `-o <file>` | where to write the assembly; `-` for stdout |
| `-O0` `-O1` `-O2` | optimization level, `-O1` by default |
| `--stats` | instruction count, image size, and how much stack and free space is left |
| `-Wall` `-Werror` | more warnings; warnings as errors |
| `--dump-tokens` `--dump-ast` `--dump-ir` `--dump-asm` | each stage, for when the output surprises you |

`--heap <n>` and `--heap-strategy <bump\|slab\|bitmap>` override decisions the
compiler makes for itself. They exist for measurement; a program should not need
them.

---

## Lexical

```c
// A line comment.
/* A block comment,
   which may span lines. */
```

Identifiers are `[A-Za-z_][A-Za-z0-9_]*`. Case matters.

| literal | example | value |
| --- | --- | --- |
| decimal | `42` | 42 |
| hexadecimal | `0x1F`, `0X1f` | 31 |
| octal | `017` | 15 — a leading `0` means octal, as in C |
| binary | `0b1011`, `0B1011` | 11 |
| with separators | `1'000`, `0b1010'1010` | 1000, 170 |
| with a suffix | `10u`, `0xFFul`, `7LLU` | 10, 255, 7 |
| character | `'A'`, `'\101'`, `'\x41'` | 65 |
| string | `"hi"` | a `byte*` to a zero-terminated copy in the image |

These are C's integer constants, all of them. A `'` between two digits is a separator,
as C23 allows, and means nothing. The suffixes `u`, `l` and `ll`, in either case and
either order, are accepted as C accepts them — but every integer here is sixteen bits,
so they change nothing. A literal takes its signedness from what it meets instead;
see [the types](#what-the-types-actually-do). Octal is the one that bites: `010` is 8,
and `09` is an error rather than nine.

Escapes inside a character or string are C's: `\n`, `\t`, `\r`, `\b`, `\a`, `\f`, `\v`,
`\\`, `\'`, `\"` and `\?`; an octal byte of one to three digits, of which `\0` is the
shortest; and `\x` followed by hexadecimal digits. `'\101'` and `'\x41'` are both 65.
A value above 255 is an error, and so is anything else after a backslash rather than
the character itself.

A literal must fit in 16 bits. `word x = 70000;` is an error, not a wrap.

---

## Types

| type | bits | signed | notes |
| --- | --- | --- | --- |
| `void` | — | — | a function's return type only; there are no `void` variables |
| `byte` | 8 | no | 0..255 |
| `sbyte` | 8 | yes | −128..127 |
| `word` | 16 | no | 0..65535, the machine's natural size |
| `sword` | 16 | yes | −32768..32767 |
| `T*` | 16 | — | a pointer, to any depth: `word**` is fine |
| `T[n]` | n·sizeof(T) | — | `n` must be a constant expression |
| `struct S` | sum of members | — | packed, no padding at all |

`sizeof` takes either a type or an expression, and the parentheses are required for
both:

```c
sizeof(word)        // 2
sizeof(byte)        // 1
sizeof(word*)       // 2 -- every pointer is a word
sizeof(struct Node) // the sum of its members
sizeof(someArray)   // the whole array, not one element
sizeof(*p)          // the size of what p points at
```

### What the types actually do

Arithmetic always happens in 16 bits. A `byte` or `sbyte` is a *storage* size: it
promotes to 16 bits to be computed with, and truncates back on assignment.

```c
byte b = 250;
b += 10;            // warns; 260 does not fit, so b is 4
word w = b + 100;   // but the addition itself is 16-bit: 104
```

Signedness is not in the bits, it is in the type — it decides which instruction the
compiler reaches for.

```c
sword s = 0 - 7;
s / 2               // -3: truncates toward zero
s % 2               // -1: the remainder takes the dividend's sign
s >> 1              // -4: an arithmetic shift, so the sign survives
s < 0               // true

word u = 65529;     // the same sixteen bits as -7
u < 0               // false: nothing unsigned is below zero
u > 1000            // true
```

Mixing the two in one operator warns, and the result is treated as signed. Keep an
expression to one family. A shift is the exception, as in C: it takes its left
operand's type, so `u >> s` is a logical shift whatever `s` is.

`sbyte` sign-extends when read, so a negative one survives the round trip:

```c
sbyte sb = 0 - 100; // stored as the byte 156
sword wide = sb;    // read back as -100, not 156
```

### Structs

Declared at file scope only, and `struct` is written at every use, as in C89.

```c
struct Point { word x; word y; };

struct Node {
    word key;
    struct Node* next;      // itself, through a pointer
};

struct Mixed {
    byte flag;              // sizeof is 5: packed, so the word sits at an odd offset
    word value;
    sword delta;
};

struct Inner { word v; };
struct Outer { struct Inner in; word w; };   // by value is fine for a member

struct Holder { word cells[4]; word used; }; // and so is an array
```

A struct must be declared at file scope; one inside a function is an error. Two of
them may name each other through pointers, and the layout pass runs to a fixpoint for
exactly that:

```c
struct A { word k; struct B* b; };      // B is not declared yet
struct B { word j; struct A* a; };
```

Members are reached with `.` on a struct and `->` through a pointer. `p->f` is
`(*p).f`.

```c
struct Point origin;
struct Point* p = &origin;
origin.x = 3;
p->y = 4;
```

A struct **can** be assigned — it copies every byte — but it cannot be passed to or
returned from a function by value:

```c
struct Point a;
struct Point b;
b = a;                          // fine: a full copy, not an alias
struct Point make() { ... }     // error: cannot return a struct by value
void take(struct Point p) { }   // error: pass a pointer instead
```

The compiler says so, and suggests the pointer.

A struct is initialized with a list, in member order and nested for nested
aggregates. Anything the list leaves out is zero.

```c
struct Point origin = {3, 4};
struct Point path[2] = {{0, 0}, {5, 5}};
```

### Unions

A union is a struct whose members all start at offset 0: it is as big as its largest
member, and writing one member rewrites the others. `union` is written at every use,
and shares one namespace with `struct`, as in C.

```c
union Word { word whole; byte halves[2]; };

union Word w;
w.whole = 0xABCD;
w.halves[0]                     // 0xAB: the machine is big-endian
union Word preset = {0x1234};   // a list fills the first member
```

### Arrays

The length may be any constant expression — a number, an enum constant, a `const`
with a constant initializer, a `sizeof`, or arithmetic on them. An initializer list
gives the elements in order, anything it leaves out is zero, and with one the length
may be left for it to count.

```c
word table[4];                  // at file scope: zeroes, because RAM arrives zeroed
word primes[5] = {2, 3, 5, 7, 11};
word partial[6] = {1, 2};       // the other four are zero
word counted[] = {10, 20, 30};  // three: the list counts it
byte name[] = "mona";           // five bytes, terminator included
```

A **local** array without an initializer holds whatever the stack held. With one, it
is filled each time the declaration runs, and its values may be anything a local's
may, not only constants. A global's or a static's must be known before the program
runs — numbers, or the address of a string, a global or a function — because it
becomes bytes in the image:

```c
const byte* names[] = {"red", "green", "blue"};     // a table of strings, in the image
```

The assembler has no label arithmetic, so `&table[1]` is not such an address. C99's
designators — `[2] = 5`, `.x = 1` — are not supported. An array cannot be assigned
whole; its elements can.

An array of arrays is written the way C writes it, and indexed the same way:

```c
word grid[3][4];                // three rows of four words
grid[2][3] = 7;
word identity[2][2] = {{1, 0}, {0, 1}};
word flat[2][2] = {1, 0, 0, 1}; // the same: C lets the inner braces go
```

An array passed to a function becomes a pointer; arrays are never copied.

```c
word total(word* values, word count) {
    word sum = 0;
    for (word i = 0; i < count; i += 1) sum += values[i];
    return sum;
}

total(table, 4);                // the name decays to &table[0]
```

The parameter may be written as an array too: `word v[]` and `word v[8]` are both a
`word*`, and `word m[][3]` points to rows of three, which is how a function takes an
array of arrays.

`a[i]` is exactly `*(a + i)`, so it works on any pointer, and there is **no bounds
checking anywhere**.

### Pointers

```c
word one = 1;
word* p = &one;
*p = 2;                         // one is now 2

word** pp = &p;
**pp = 3;                       // and now 3
```

`&` works on a variable, an array element, a struct member — directly or through a
pointer — and on another pointer.

```c
&someVariable
&table[2]
&origin.y
&p->x
&p                              // a word**
```

Adding to a pointer moves it whole elements:

```c
struct Point points[3];
&points[0] + 1 == &points[1]    // true
```

**Subtracting two pointers gives bytes, not elements.** This differs from C:

```c
&points[1] - &points[0]         // 4, the size of a Point -- not 1
```

A function's name is its address — `onInterrupt` and `&onInterrupt` are the same
pointer — and `__setisr` takes either:

```c
__setisr(onInterrupt);
```

Function pointers have [a section of their own](#function-pointers).

---

## Declarations

A declaration is a statement, so it may appear wherever one may, and it is in scope
from that point to the end of its block. The initializer is optional.

```c
word counter = 0;
word* cursor;                   // no initializer: uninitialized
byte flag = 1;
struct Point origin;            // a struct or array declares fine without one
```

**A local with no initializer holds whatever was already in its frame slot** — the
machine has no zeroing pass, and adding one would cost instructions every call. A
global with no initializer is zero, and that is not a promise the compiler keeps but
one the machine does: RAM arrives cleared, which is also what the allocators rely on.

A global's initializer must be known before the program runs, because there is nowhere
to run code before `main`: a number, or the address of a string, a global or a
function.

```c
word size = 2 * 21;             // fine, folded to 42
word half = size / 2;           // error: must be a constant expression
```

Top-level items — functions, globals and structs — are all in scope throughout the
file regardless of order, so nothing needs declaring ahead of its use, and nothing may
be declared twice. The one exception is C's: a constant used by another declaration —
an array length, another constant's initializer — has to come first.

### `const`

`const` makes something read-only, and goes wherever C lets it: before the type,
after it, or after any `*`.

```c
const word WIDTH = 16;          // WIDTH may not be assigned
const byte* name;               // the bytes may not be written through name...
name = "mona";                  // ...but name itself may move
byte* const cursor = buffer;    // cursor may not move, but *cursor may be written
```

Assigning to it, stepping it or compounding it is an error, and so is writing through
a pointer to const — `*p`, `p[i]` and `p->field` alike. Taking the address of
something const gives a pointer to const, and handing that to a plain pointer warns,
as C does; a cast says it was meant. A const local needs an initializer, because
nothing could give it a value afterwards. Otherwise `const` is a promise the compiler
checks, with one exception below.

**A `const` scalar with a constant initializer is itself a constant**, so it can size
an array and label a case. C allows that only from C23's `constexpr`; C++ always has.

```c
const word COLUMNS = 16;
byte row[COLUMNS];              // sixteen bytes
```

It is also read as its value: `x < COLUMNS` compares with the immediate 16, the way
an enumerator would, rather than loading `COLUMNS` from memory. A `const` pointer with
a constant address folds the same way, which is what makes this the cheapest way to
name a memory-mapped device:

```c
byte* const SCREEN = 4096;      // the text display
SCREEN[3] = 'A';                // a store to 4096 + 3, with nothing loaded first
```

The variable still exists for anything that takes its address, and is dropped from
the image when nothing does.

### `enum`

```c
enum Direction { UP, RIGHT, DOWN, LEFT };      // 0, 1, 2, 3
enum { SPEED = 4, FAST = SPEED * 2, };         // anonymous, and a trailing comma is fine

enum Direction facing = RIGHT;
switch (facing) {
    case UP: ...
}
```

Each name is a `word` constant, one more than the one before unless it says otherwise,
and a value may use the names before it. An enumerator has no storage — it is its value
wherever it appears — so it can size an array, label a case or initialize a global.
`enum Name` as a type is a `word` with a better name. An enum may also be declared
inside a function, and then its names belong to that block.

### `static`

A `static` local is a global that only its block can name: one copy, kept between
calls, and initialized once, in the image, rather than each time the line is reached.

```c
word nextId() {
    static word issued = 0;     // 0 before the first call, and never reset
    issued++;
    return issued;
}
```

Its initializer has to be a constant, for the same reason a global's does. At file
scope `static` is accepted and changes nothing: with one file and no linker, internal
linkage is what everything has already.

---

## Expressions

Precedence, loosest first. It is C's, including C's placement of the bitwise
operators below the comparisons.

| | operators | |
| --- | --- | --- |
| 1 | `,` | left, then right; the value is the right |
| 2 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` | right associative |
| 3 | `?:` | right associative; only the chosen arm is evaluated |
| 4 | `\|\|` | short-circuits |
| 5 | `&&` | short-circuits |
| 6 | `\|` | |
| 7 | `^` | |
| 8 | `&` | |
| 9 | `==` `!=` | |
| 10 | `<` `<=` `>` `>=` | |
| 11 | `<<` `>>` | |
| 12 | `+` `-` | |
| 13 | `*` `/` `%` | |
| 14 | `-` `+` `!` `~` `&` `*` `++` `--` unary, `(type)` casts, `sizeof` | right associative |
| 15 | `a[i]` `f(...)` `s.f` `p->f` `x++` `x--` | |

Two consequences worth remembering, both of which surprise people in C too:

```c
4 + 5 & 7           // 1, because + binds tighter than &
1 + 2 << 3          // 24, because + binds tighter than <<
```

`!` yields exactly 0 or 1. `&&` and `||` short-circuit, so the right-hand side is not
evaluated when the left decides the answer.

`++` and `--` work in both positions. Prefix yields the new value and postfix the old
one, a pointer steps by the size of what it points at, and the target's address is
worked out once, so a subscript with a side effect runs once. Compound assignment
evaluates its target once in the same way. Any assignment yields what its target holds
afterwards, so on a `byte` at 255, `++b` is 0, not 256.

```c
k++                     // the old value; k is one higher afterwards
++k                     // the new value
p++                     // a word* moves two bytes, a struct pointer its size
w[next()]++             // next() is called exactly once
++w[next()]             // so it is here
w[next()] += 2          // and here
```

A cast narrows, reinterprets, or changes nothing, and binds tighter than any binary
operator. It retires `& 255` as the way to say "the low byte".

```c
(byte)300               // 44
(sword)65535            // -1: the same bits, read as signed
(byte*)&w               // the same address, now a byte pointer
```

`?:` evaluates only the arm it picks, which makes it safe over a pointer that may be
null, and it nests to the right. With it, `abs`, `min` and `max` are a line each — and
because they are your code, their signedness comes from the operand types rather than
from a builtin's fixed signature.

```c
n < 5 ? 1 : n < 10 ? 2 : 3      // 2 when n is 7
p != 0 ? p->x : 0               // never dereferences a null p
```

The comma operator evaluates its left side for its effects and yields its right. It
binds loosest of all, so `a = 1, 2` assigns 1. A comma between call arguments still
separates them, and in an initializer the operator needs parentheses.

```c
for (i = 0, j = 9; i < j; i++, j--) { }   // its natural home
word r = (1, 2);                          // 2
```

`-x` on an unsigned `word` is legal and wraps, but the idiom in this codebase is
`0 - x`, because it reads as what the machine does.

---

## Statements

```c
if (a < b) {
    ...
} else if (a < c) {             // just an else whose statement is another if
    ...
} else {
    ...
}
```

Braces are optional around a single statement.

```c
while (count < 3) count += 1;

while (1) { ... }               // the endless loop idiom

for (word i = 0; i < 5; i += 1) { ... }
for (; i < 5; i += 1) { }       // any of the three parts may be empty
for (word i = 0; ; i += 1) { }  // an empty condition means true

break;                          // leaves the nearest loop or switch
continue;                       // next iteration of the nearest loop
```

`do`/`while` tests after the body, so the body always runs at least once:

```c
do {
    m = m * 2;
} while (m < 100);
```

```c
switch (n) {
    case 1: hits += 1;          // no break, so it falls through
    case 2: hits += 10;
            break;
    case 3:
    case 4: hits += 100;        // several labels, one arm
            break;
    default: hits = 0;
}
```

Case labels must fold to a constant; the switched value need not. `default` is
optional, and a switch that matches nothing does nothing. An arm holds statements
rather than a block, which is what gives the fall-through.

A block is a statement, and a declaration inside one is scoped to it:

```c
word outer = 1;
{
    word inner = 2;             // gone at the closing brace
    outer += inner;
}
```

`goto` jumps to a label in the same function. What it is still the clearest way to
write is leaving two loops at once:

```c
for (word i = 0; i < 10; i++) {
    for (word j = 0; j < 10; j++) {
        if (grid[i][j] == target) goto found;
    }
}
return 0;
found:
return 1;
```

It may leave blocks, or move within one, but not enter one, and it may not jump
forward past a declaration: both would reach code whose variables nothing set up. C
allows them and leaves the result to chance; the compiler refuses them. A label that
nothing jumps to is warned about.

`return` with a value in a value-returning function, bare in a `void` one.

---

## Functions

```c
word twice(word n) {
    return n + n;
}

void store(word* into, word value) {
    *into = value;
    return;                     // optional
}

word noArguments() { return 11; }
```

**Order in the file does not matter.** Every top-level name is in scope throughout, so
a function may call one defined later without declaring it first.

```c
word main() {
    return definedLater(6);
}

word definedLater(word n) {
    return n * n;
}
```

A prototype is accepted all the same, as in C, and checked. Parameter names are
optional in one, and `(void)` means no parameters, in a prototype and a definition
alike.

```c
word isOdd(word);               // no parameter name needed
void reset(void);

word isEven(word n) { return n == 0 ? 1 : isOdd(n - 1); }
word isOdd(word n)  { return n == 0 ? 0 : isEven(n - 1); }
```

Every declaration of a function must agree with every other — the return type and
each parameter's type — and they may come before or after the definition, any number
of times. A prototype nothing uses costs nothing. Calling one that is never defined is
an error at the call, where C would have waited for the linker.

Recursion works, including mutual recursion. The compiler computes the stack
requirement from the call graph where it can; a recursive program has no such number,
so it gets a run-time check that prints `STACK OVERFLOW` on the text display and halts
rather than quietly overwriting the code.

```c
word factorial(word n) {
    if (n <= 1) return 1;
    return n * factorial(n - 1);
}
```

The first argument travels in a register and the rest on the stack, which is worth
knowing only because it is why a one-argument function is cheaper than a four-argument
one. Nothing in the language exposes it.

### Function pointers

Declared the way C declares them, with the name inside `(*...)`:

```c
word add(word a, word b) { return a + b; }
word mul(word a, word b) { return a * b; }

word (*op)(word, word) = add;               // a function's name is its address
op = &mul;                                  // and so is &name
op(6, 7)                                    // 42
(*op)(6, 7)                                 // the same call, written the older way

word (*ops[2])(word, word) = {add, mul};    // a table of them, in the image
ops[1](6, 7)                                // 42

struct Button { word id; void (*press)(void); };
word apply(word (*f)(word), word x);        // as a parameter
(word (*)(word))address                     // and in a cast, with no name
```

A pointer takes only a function of its own signature, or 0 — anything else would
pass the wrong arguments or read a result that was never there — and a call through
one is checked against the signature it carries, like any other call.

The call itself is `CALL [A]`. The compiler cannot know which function a pointer
holds, but it knows which it *could*: only a function whose address was taken
somewhere — by name, with `&`, or in a global's initializer — can have got into one.
So an indirect call counts as reaching every such function. The stack bound stays a
number, a cycle through a pointer is found and guarded like any other recursion, and
the interrupt check still sees everything a handler can reach. The price is caution:
once a program installs a handler, every address-taken function counts as one that
could be. A pointer made from a number with a cast is the one thing this cannot see.

---

## The builtins

Twenty-five names. Eight compile to a single instruction; the heap pair and fifteen
helpers become calls into small runtime routines, emitted only when a program actually
uses them, so an unused one costs nothing at all. They are spelled with leading
underscores, which is C's own convention for names reserved to the implementation: a
program may declare its own `free` or `strlen` without colliding with anything, so
there is no header to include.

The machine's peripherals live in a **port space separate from memory**, unreachable
by any pointer. `__in` and `__out` are the only way in; without them the whole of the
hardware would be invisible to a program.

### `word __in(word port)`

Reads a port.

```c
word status = __in(5);          // KBDSTATUS: is a key waiting?
word key = __in(6);             // KBDDATA: read it, which clears the request
word roll = __in(10);           // RNDGEN: a fresh random word every read
```

### `void __out(word port, word value)`

Writes a port.

```c
__out(7, 2);                    // VIDMODE 2: bitmap
__out(7, 3);                    // VIDMODE 3: clear all 65536 pixels, in one instruction
__out(8, y * 256 + x);          // VIDADDR: where
__out(9, 255);                  // VIDDATA: what -- white
```

Write the port as a **literal** in anything hot. `OUT` takes its port as an immediate
or from a register and nothing else, so a port held in a variable must be loaded into
one first — and with every register busy the compiler frees one by pushing it. In
`cube.mona`'s inner loop that was three instructions a pixel.

| port | name | |
| --- | --- | --- |
| 0 | IRQMASK | one bit per device: keypad 1, timer 2, graphics 4 |
| 1 | IRQSTATUS | *read-only* — which devices are asking |
| 2 | IRQEOI | write back what you handled |
| 3 | TMRPRELOAD | instructions between timer interrupts |
| 4 | TMRCOUNTER | *read-only* — how many are left, counting down once per instruction |
| 5 | KBDSTATUS | *read-only* — a key is waiting |
| 6 | KBDDATA | *read-only* — the key; reading it lowers the request |
| 7 | VIDMODE | 0 off, 1 text, 2 bitmap, 3 clear, 4 reset |
| 8 | VIDADDR | byte address in video memory: `y * 256 + x` |
| 9 | VIDDATA | the palette index to store there |
| 10 | RNDGEN | *read-only* — a random word |

**Writing a read-only port faults the CPU**, and there is no recovering from it: the
machine has no vector for the exception, so it latches into fault mode and every
instruction after it throws. The same goes for any port above 10. The compiler rejects
both when the port is a literal; a computed port is never judged, because a loop over
the video registers is a reasonable thing to write.

The palette is a 3-3-2 RGB cube, so a colour is arithmetic rather than a lookup:
`index = red * 32 + green * 4 + blue`, red and green 0..7, blue 0..3. 255 is white,
224 red, 28 green, 3 blue, 252 yellow.

The channels are stepped by 36, 36 and 85, so index 224 is `(252, 0, 0)` and not
`(255, 0, 0)`; index 255 is the single entry special-cased to true white. That matters
only if you are matching a colour exactly.

### The graphics card has 64 KB of its own

Separate from the machine's 4 KB, reachable only through these two ports, and it means
two entirely different things depending on the mode.

**`VIDMODE 2`, bitmap.** All 65,536 bytes are the screen: 256x256, one byte a pixel,
each byte a palette index, `VIDADDR = y * 256 + x`. No scrolling, no sprites, no
memory left over, and the palette is ROM.

**`VIDMODE 1`, tile.** The simulator calls it text mode, which undersells it. It is a
tile engine, and the 64 KB is laid out as one:

| range | size | contents |
| --- | --- | --- |
| `0x0000`..`0x7FFF` | 32 K | tile map: 128x128 cells, two bytes each — tile index, then colour index |
| `0x8000`..`0x9FFF` | 8 K | 256 tile definitions, 32 bytes each: 16x16 pixels, one bit per pixel |
| `0xA000`..`0xA2FF` | 768 B | palette: 256 entries of three bytes, RGB |
| `0xA301` | 1 B | background colour index |
| `0xA302`..`0xA303` | 2 B | HScroll, in pixels |
| `0xA304`..`0xA305` | 2 B | VScroll, in pixels |
| `0xA306`..`0xA325` | 32 B | eight sprites, four bytes each: tile, colour, x, y |
| `0xA326`..`0xFFFF` | 23,754 B | free; the card never reads it |

The map is 2048x2048 pixels seen through a 256-pixel window. **The scroll registers
are in pixels**, not tiles, and clamp at 1792 — exactly 2048 - 256 — so the window
reaches every corner of the map one pixel at a time.

The 256 tile definitions are loaded from ROM at reset with **IBM CP437** — not just
letters and digits, but box-drawing, dither patterns and block glyphs. Four of those
are exactly a two-bit lookup:

| tile | | |
| --- | --- | --- |
| `0x20` | blank | both halves off |
| `0xDF` | upper half | top on |
| `0xDC` | lower half | bottom on |
| `0xDB` | solid | both on |

So **every cell is two independently settable square pixels**, and the map becomes a
128x256 grid of them, each cell with its own colour, scrollable a pixel at a time, with
no tile definitions uploaded at all. That is the cheapest picture this machine can
draw — [../../examples/3-graphics/blocks.mona](../../examples/3-graphics/blocks.mona) is a scrolling landscape whose
entire artwork is those four constants.

The *horizontal* halves do not work this way: `0xDD` is ten columns wide against
`0xDE`'s eight, so they overlap by two and will not tile. Only the vertical split is
clean.

Writing a letter's code into the map prints that letter, which is the same mechanism. They are ordinary memory:
**overwrite one and it is your shape from then on.** A set bit takes the cell's colour
index and a clear bit shows the background colour, so a cell has two colours out of
256. Note that redefining tile 0 paints the whole map, because every cell a program
has not written is `(tile 0, colour 0)`.

The eight sprites are 16x16, drawn from the same definitions in one colour, always
over the map, and their clear bits are **transparent** rather than background — the
one thing a tile cannot do. They are in screen space, so scrolling does not move them,
and `x` and `y` are single bytes, so one near an edge is clipped rather than wrapped.
A tile index of 0 means off.

```c
void defineTile(word which, word row) {         // 32 bytes: sixteen 2-byte writes
    word at = 32768 + which * 32;               // 0x8000 + 32 * which
    word end = at + 32;
    while (at < end) {
        __out(8, at);
        __out(9, row);
        at += 2;
    }
}

void setCell(word x, word y, word tile, word colour) {
    __out(8, (y * 128 + x) * 2);
    __out(9, tile * 256 + colour);              // one cell, one write
}

word main() {
    __out(7, 1);                                // tile mode
    defineTile(1, 65535);                       // 0xFFFF: a solid block
    setCell(0, 0, 1, 224);                      // in red
    __out(8, 41728); __out(9, 3);               // 0xA301: background = blue
    __out(8, 41734); __out(9, 1 * 256 + 28);    // 0xA306: sprite 1 = tile 1, green
    __out(8, 41736); __out(9, 100 * 256 + 50);  // 0xA308: at x=100, y=50
    __out(8, 41730); __out(9, 8);               // 0xA302: scroll right 8 pixels
    return 0;
}
```

**Video memory can be read.** Writing `VIDADDR` pre-loads `VIDDATA` from that address,
so a read is two instructions:

```c
word peek(word at) {
    __out(8, at);
    return __in(9);             // a big-endian word in tile mode; one byte in bitmap
}
```

In tile mode nothing above `0xA326` is ever rendered, which leaves 23,754 bytes the
card will not touch — five and a half times the machine's whole RAM — usable as slow
but real storage.

Four differences between the modes catch people:

- A `VIDDATA` write stores **two** bytes in tile mode (the high byte at `VIDADDR`, the
  low at `VIDADDR + 1`) and **one** in bitmap mode. Two bytes is exactly one map cell,
  or half a sprite record.
- The palette is programmable in tile mode only. Bitmap mode looks colours up in the
  card's ROM and ignores `0xA000` entirely.
- `VIDMODE 3` clears all 64 KB in bitmap mode but only the 32 KB map in tile mode, so
  definitions, palette, scroll and sprites survive it. `VIDMODE 4` resets everything
  and reloads the tile ROM.
- `VIDADDR` never auto-increments, in either mode.

**While the display is on the card raises interrupt line 2** — IRQMASK bit value `4` —
every 20 ms. That is a real 50 Hz wall-clock tick and the only one the machine has:
`TMRPRELOAD` counts instructions, so its rate follows the simulator's speed setting
while this does not.

All of this is pinned by
[`TileModeIT`](../../src/test/java/dev/madlador/e2e/TileModeIT.java), which drives the
real card from Mona and asserts on the pixels it renders rather than on the bytes a
program wrote.

### `void __sti()` and `void __cli()`

Allow and block interrupt delivery. Delivery needs **both** the device's bit in
IRQMASK and this global enable. Three devices can interrupt: the keypad on line 0
(mask 1), the timer on line 1 (mask 2), and the graphics card's 50 Hz refresh on
line 2 (mask 4).

### `void __setisr(word address)`

Installs the interrupt handler. There is one vector, at `0x0003`, shared by every
device, so a program has exactly one handler and works out who called by reading
IRQSTATUS. The address is stored at run time, so handlers may be swapped.

```c
word ticks = 0;

void onIrq() {
    word pending = __in(1);         // IRQSTATUS: who asked
    if (pending & 2) {              // bit 1: the timer
        ticks = ticks + 1;
    }
    __out(2, pending);              // IRQEOI: acknowledge, or IRET returns
}                                   // straight back into the same request

word main() {
    __setisr(&onIrq);
    __out(0, 2);                    // IRQMASK: the timer only
    __out(3, 1000);                 // TMRPRELOAD: every 1001 instructions
    __sti();
    while (1) { ... }
}
```

A handler cannot interrupt itself: entering one clears the global enable and `IRET`
restores it. Keep it short — everything it does happens while the program is stopped.

**`KBDSTATUS` is a bit field, not a flag.** 1 is a key going *down*, 2 is one coming
*up*, and 4 is added to either when a previous event was never collected — so the
values a program sees are 1, 2, 5 and 6. Reading `KBDDATA` is what returns it to 0, so
**drain it on every event and decide afterwards**:

```c
word status = __in(5);
if (status == 0) return;        // nothing waiting
word key = __in(6);             // collect it, whatever it was
if ((status & 1) == 0) return;  // 2 is a release, not a press
```

Testing `__in(5) != 1` and returning early never drains a release: the port latches at
2, every later press reads 5, and the keyboard appears dead. That shipped in
`snake.mona`. Note also that one physical keypress delivers **two** interrupts, down
and up, with the same `KBDDATA` — a handler that counts presses reads double.

**Read `KBDDATA` before you acknowledge.** The keypad is the only *level*-triggered
device here. A keypress raises both the controller's level bit and its status bit;
reading `KBDDATA` lowers the level and *only* the level, and `IRQEOI` clears the status
bit — except that `IRQEOI` is `status ^= value` followed by `status |= level`, so it
restores anything the level still asserts. Both steps are needed, in that order, and
each way of getting it wrong fails without looking like an interrupt bug:

| what the handler does | what happens |
| --- | --- |
| reads `KBDDATA`, then `IRQEOI` | correct: one keypress, one entry |
| `IRQEOI`, then reads `KBDDATA` | the level was still high, so the status bit comes straight back and the handler runs **twice** for one press — nothing hangs, a counter is just doubled |
| `IRQEOI`, never reads `KBDDATA` | the level never falls, so every acknowledgement re-raises the request and the program stops making progress |

The second is the one that bites, because it looks like a game reacting twice to one
key rather than like anything to do with interrupts.
[../../examples/2-hardware/interrupt.mona](../../examples/2-hardware/interrupt.mona) has the order right, and
[`KeypadAckIT`](../../src/test/java/dev/madlador/e2e/KeypadAckIT.java) keeps it that way.

Note also that `IRQEOI` being an XOR means acknowledging a line that is *not* raised
**sets** it. Write back what you read from `IRQSTATUS`, never a constant you assumed.

The timer counts **instructions, not time**, because every instruction on this machine
is one clock tick. A second is however many instructions the simulator runs per
second, which is the speed in the Speed menu: with the preload at 1000, ticks per
second is the speed in kHz.

### `word __alloc(word size)`

Allocates `size` bytes and returns the address, or **0** when there is no room. A
program that calls it gets a heap automatically — there is no flag to remember, since
the need is obvious from the source.

```c
struct Node* node = __alloc(sizeof(struct Node));
if (node == 0) return 1;            // always check; the machine has 4 KB
node->key = 7;
node->next = head;
```

Write `sizeof`, not the number. `__alloc(6)` works today and keeps working, silently
and wrongly, the moment someone adds a member.

### `void __free(word pointer)`

Returns a block. Freeing 0 is allowed and does nothing.

```c
__free(node);
```

`__free` checks its argument: a pointer that is outside the heap, misaligned, not the
start of a block, or already freed never came from `__alloc`, so instead of quietly
corrupting the heap it prints `HEAP CORRUPT` on the text display and halts.

**Neither is re-entrant.** If an interrupt handler allocates *and* the program does
too, both can be handed the same memory — each allocator reads a word, decides from
it, and writes it back. The compiler warns when it can see both; the fix is `__cli()`
and `__sti()` around the program's own allocations.

**But closing that window opens another one.** An interrupt that arrives while
interrupts are off is *lost*, not deferred: `CLI` clears the mask and then dispatches
anything already pending, while `STI` only sets the mask and never samples. A device
that raises during the critical section latches inside the CPU, `__sti()` does not
flush it, and the controller will not raise it again because its own output is still
asserted. Nothing arrives, ever again.

Re-arm the controller by toggling its mask register, which lowers the line and
re-asserts it:

```c
__cli();
struct Node* n = __alloc(sizeof(struct Node));
__sti();
__out(0, 0);                // IRQMASK off...
__out(0, mask);             // ...and back on, which re-delivers what was stranded
```

Measured: a program that took three timer ticks, ran `__cli()`, spun for twenty-five
timer periods and then ran `__sti()` counted **zero** further interrupts. The same
program with those two extra lines counted thousands. See
[../design/coverage.md](../design/coverage.md#4-interrupts--where-the-traps-are).

### `void __vwrite(word vaddr, word from, word count)`

Copies `count` bytes of RAM into video memory.

```c
byte tile[32];
// ... build the shape in RAM ...
__vwrite(32800, tile, 32);      // 0x8020: tile definition 1
```

**There is no DMA on this machine.** Ports 0 to 10 are all of it, and the only road
into the card's 64 KB is one `VIDADDR`/`VIDDATA` pair at a time. So every program that
loads a tile set, a palette or a sprite table writes this same loop — and writes it
slightly differently, because how much one `VIDDATA` write moves depends on the mode:
two bytes in tile mode, one in bitmap. This reads `VIDMODE` and branches, so a byte
count means a byte count either way.

RAM is big-endian and `VIDDATA` splits a word the same way round, so in tile mode a
word goes straight through untouched, no shuffling. An odd count ends on a lone byte
that cannot be written by itself, so the tail reads the pair back, replaces one half
and writes it again: **the byte after the range is left as it was.**

Measured, against the obvious hand-written loop:

| | tile mode | bitmap mode |
| --- | --- | --- |
| `__vwrite` | 4.5 instructions/byte | 10 |
| `__vread` | 4.5 | 9 |
| `__vfill` | 4.0 | 8 |
| a Mona loop doing the same | 14 | 16 |

Tile mode is cheaper per byte because one `VIDDATA` write moves two of them. The gap
against hand-written code is not cleverness — it is that the helper keeps the address
and the pointer in `B` and `C` and ends on a precomputed limit, which a Mona loop
cannot do because it has locals to spill.

### `void __vread(word vaddr, word into, word count)`

The same road in the other direction.

```c
byte back[32];
__vread(60000, back, 32);
```

This works because writing `VIDADDR` pre-loads `VIDDATA` from that address. In tile
mode the card never looks above `0xA326`, which leaves **23,754 bytes it will not
disturb** — five and a half times the machine's whole RAM, and the only place a 4 KB
program has room to spare. Slow storage, but real.

### `void __vfill(word vaddr, word value, word count)`

Fills `count` bytes of video memory with a 16-bit pattern.

```c
__vfill(0, 0, 2048);            // clear the top-left corner of the tile map
__vfill(0, 0x0503, 512);        // or fill it with tile 5 in colour 3
```

In bitmap mode every byte becomes the low half of `value`, because a pixel is a
palette index. In tile mode the whole word goes to each pair, which is exactly one map
cell — high byte the tile, low byte the colour.

### `void __waitframe()`

Blocks until the graphics card's next refresh, 50 times a second.

```c
word main() {
    __out(7, 1);
    while (1) {
        __waitframe();          // 20 ms of wall time, every time
        // ... move something, draw it ...
    }
}
```

This is the machine's only wall clock. `TMRPRELOAD` counts *instructions*, so its rate
follows the simulator's speed setting; the card's refresh does not.

It **polls** `IRQSTATUS` rather than taking the interrupt, so it needs no handler, no
`__setisr` and no `__sti` — frame pacing is available to a program that wants nothing
else to do with interrupts. It ORs the card's bit into `IRQMASK` on every call, because
the controller drops a trigger for a masked-out line rather than remembering it. If the
display is off it **returns immediately**, since there would be no refresh to wait for.

### `word __mulhi(word a, word b)`

The high 16 bits of `a * b`, unsigned.

```c
__mulhi(1000, 1000)             // 15, because 1000 * 1000 is 0x000F4240
1000 * 1000                     // 16960, which is 0x4240 -- the half MUL keeps
```

`MUL` is 16&times;16 into 16 and the top half is simply gone. That is the machine's
largest arithmetic hole, and the reason
[cube.mona](../../examples/3-graphics/cube.mona) works at a fixed-point scale of 128 rather than 256.
Recovering it takes four byte-wide multiplies and the carries between them — about
thirty instructions, too many to inline and too fiddly to write twice.

For fixed point at scale 2<sup>16</sup>, `__mulhi` alone *is* the multiply. For a
smaller scale, combine the halves:

```c
// a and b at scale 256, result at scale 256
word product = (__mulhi(a, b) << 8) | ((a * b) >> 8);
```

That is for unsigned numbers. For signed ones at scale 256 — anything that involves
`__sin` or `__cos` — use [`__fixmul`](#sword-__fixmulsword-a-sword-b), which does
this and gets the signs right.

[`MachineBuiltinsIT`](../../src/test/java/dev/madlador/e2e/MachineBuiltinsIT.java) checks
it against exact arithmetic over 1,344 pairs, every crossing of the interesting
boundaries included, because two of its carries are invisible in a small test.

### `word __ticks()`

The timer's counter, which falls by one for every instruction the CPU executes.

```c
__out(3, 30000);                // TMRPRELOAD: the counter only runs once it is set
word before = __ticks();
work();
word cost = before - __ticks(); // exactly how many instructions that took
```

It **counts down**, so the earlier reading is the larger one. This is the only
self-measurement the machine has: one `IN 4`, no overhead, and the number is exact
rather than sampled — halted steps included.

It is the *timer's* counter and not a private one, so a program using timer interrupts
is reading the same register that drives them, and it wraps at the preload rather than
counting freely. Set `TMRPRELOAD` high if you mean to measure something long.

### `word __getkey()`

The keypad's status and its key, together: `(status << 8) | key`, or **0** when nothing
is waiting.

```c
word k = __getkey();
if (k != 0) {
    word status = k >> 8;
    word key = k & 255;
    if ((status & 1) != 0) press(key);       // 1 is a key going down
}
```

`KBDSTATUS` is a bit field rather than a flag — 1 down, 2 up, and 4 added to either
when a previous event was never collected — and **reading `KBDDATA` is what returns it
to 0**. So the only correct shape is collect first, decide afterwards. Testing the
status against 1 and returning early never drains a release: the port latches at 2,
every later press reads 5, and the keyboard appears dead. That shipped in
`snake.mona`.

This is the one builtin that encodes a policy rather than exposing a machine primitive,
and that is deliberate — it makes the trap unskippable.

### `void __halt()`

Stops the machine, where it stands.

```c
if (impossible()) __halt();
```

Returning from `main` does this already; the builtin is for stopping from somewhere
nested without threading a result back out. A halted CPU still consumes a tick per
step, so an interrupt can start it again.

### `void __memcpy(word dst, word src, word count)` and `void __memset(word dst, word value, word count)`

```c
byte line[16];
__memset(line, ' ', 16);                    // sixteen spaces
__memcpy(&line[1], &line[0], 15);           // shift right by one: overlap is safe
```

`__memcpy` copies bytes, and unlike C's it survives overlap in either direction: when
the destination is above the source it copies from the top down, which is what C calls
`memmove`. `__memset` stores the low byte of `value`. Both count in bytes and take the
count literally, so eight words are `__memcpy(dst, src, 16)` — or `sizeof` of the
array.

### `__strlen`, `__strcpy` and `__strcmp`

```c
word  __strlen(byte* s)
void  __strcpy(byte* dst, byte* src)
sword __strcmp(byte* a, byte* b)
```

```c
byte name[16];
__strcpy(name, "mona");
__strlen(name)                  // 4
__strcmp(name, "mona")          // 0
__strcmp("abc", "abd")          // -1: negative, so "abc" sorts first
__strcmp("\xC8", "a")           // positive: bytes compare unsigned, as in C
```

C's, down to the result: the difference of the first two bytes that differ, each read
unsigned. It is a `sword`, so `< 0` means what it says. `__strcpy` copies the
terminator and checks no length — the destination has to be big enough, as in C.

### `word __sqrt(word n)`

```c
__sqrt(99)                      // 9: rounded down
__sqrt(65535)                   // 255
```

The integer square root by the digit-by-digit method: at most eight steps, and no
multiply or divide. Checked against Java at every perfect square and either side of it.

### `sword __sin(word angle)` and `sword __cos(word angle)`

Fixed point at both ends: **256 steps to a turn**, and a result in which **256 is
1.0**.

```c
__sin(64)                       // 256: a quarter turn
__sin(32)                       // 181, which is 0.707 * 256
__cos(128)                      // -256: half a turn
__sin(a + 256) == __sin(a)      // a whole turn wraps, so a byte angle wraps by itself
```

Both read one quarter-wave table of 65 words. A program that uses either gets it once;
one that uses neither gets nothing. Checked at all 256 angles.

### `sword __fixmul(sword a, sword b)`

`(a * b) >> 8`, computed on the whole 32-bit product: the multiply for numbers with
eight fractional bits, and the partner of `__sin` and `__cos`.

```c
__fixmul(384, 512)              // 768: 1.5 * 2.0 is 3.0
__fixmul(-256, 768)             // -768

// rotate (x, y) by angle a
sword rx = __fixmul(x, __cos(a)) - __fixmul(y, __sin(a));
sword ry = __fixmul(x, __sin(a)) + __fixmul(y, __cos(a));
```

It is signed and rounds toward negative infinity, as an arithmetic shift does, so
`__fixmul(-1, 1)` is -1 rather than 0. The result wraps when the true answer does not
fit in a `sword`. It is built on `__mulhi`, which comes along with it.

### `word __random()`

```c
word coin = __random() & 1;             // mask when the range is a power of two
word roll = __random() % 6 + 1;         // otherwise %, which is very slightly biased
```

One `IN 10`: `RNDGEN` hands out a fresh word on every read, uniform over 0 to 65535.
The same as `__in(10)`, with a name. A range that is not a power of two does not divide
65,536 evenly, so `% 6` favours four of its six results — by one part in 10,922, which
is nothing for a die and something for a statistic.

### Getting data to the card, and getting the RAM back

The card's memory is the one place a 4 KB machine has room to spare, and the flow is
always the same three steps: **build it in RAM, push it across, take the RAM back.**
The heap is the natural place to build it, precisely because you want the space
returned.

```c
word main() {
    __out(7, 1);                            // tile mode

    // Build 32 tile definitions -- a whole 1 KB of shapes -- in a buffer that only
    // has to exist for as long as it takes to push them across.
    byte* shapes = __alloc(32 * 32);
    if (shapes == 0) return 1;
    for (word i = 0; i < 32 * 32; i += 1) {
        shapes[i] = generate(i);
    }

    __vwrite(32768, shapes, 32 * 32);       // 0x8000: into the card
    __free(shapes);                         // and the kilobyte is yours again

    // From here the shapes live in video memory. The program keeps none of them.
    ...
}
```

A kilobyte is a quarter of everything this machine has, so being able to hand it back
is not a nicety. The same pattern loads a palette (`0xA000`, 768 bytes), a sprite table
(`0xA306`, 32 bytes) or a screenful of map, and the same pattern in reverse —
`__vwrite` a working set out, `__free`, then `__vread` it back when needed — turns the
23,754 free bytes above `0xA326` into swap space.

What there is **not** is a hardware transfer. Every byte crosses through `VIDADDR` and
`VIDDATA`, one write at a time, so a kilobyte is a few thousand instructions. Push data
across once during setup rather than per frame.

Which allocator you get is chosen by reading the whole program: a bump pointer if it
never frees, fixed cells if every size is the same constant, a general bitmap
otherwise. Nothing in the language changes — see the README for why it matters.

---

## Writing to the text display

Not a builtin: the 32-character text display is **memory mapped** at `0x1000`, so it
is an ordinary array.

```c
void putChar(word at, byte ch) {
    byte* screen = 4096;            // 0x1000
    screen[at] = ch;
}
```

---

## What is not here

`typedef`, designated initializers, variadic functions, a preprocessor, separate
compilation, floating point.

None of those is coming, and [../design/roadmap.md](../design/roadmap.md) says why for each. For `typedef`
and the preprocessor the short version is that every type starting with a keyword is
what lets `(byte)x` and `(x)` be told apart without a symbol table.

Floating point is absent because the machine has none. Fixed point is the answer —
`__fixmul`, `__sin` and `__cos` are built for it — and
[../../examples/3-graphics/cube.mona](../../examples/3-graphics/cube.mona) is what it looks like.

---

## What the compiler refuses

Every one of these is a real message, caret and note included. They are worth reading
once, because each marks a place where the language deliberately stops rather than
guessing.

```
error: a function cannot return a struct by value: 'struct Point'
    note: pass 'struct Point*' instead

error: a parameter cannot be a struct by value: 'struct Point'
    note: pass 'struct Point*' instead

error: too many values for 'word[2]'

error: cannot assign 'word (*)(word)' to 'void (*)(void)'
    note: the signatures differ, and a call through it would be wrong

error: 'goto found' jumps over the declaration of 'count'
    note: move the declaration above the goto, or the label above the declaration

error: a global initializer must be a constant expression
    note: a number, or the address of a string, a global or a function

error: a variable may not have type 'void'

error: number '70000' does not fit in 16 bits
    note: the largest value a word can hold is 65535 (0xFFFF)

error: conflicting types for 'f'
    note: it was first declared as word f(word)

error: 'g' is declared but never defined
    note: a prototype promises a definition somewhere in the file

error: 'x' is const, so it cannot be assigned to

error: an array length must be a constant expression
    note: a const with a constant initializer, an enum constant or sizeof will do

error: the program is about 16760 bytes, which does not fit in 4096 bytes of RAM

error: port 4 is TMRCOUNTER, which is read-only
    note: writing it faults the CPU; use __in(4) to read it

error: there is no port 11; the machine has 0 to 10
    note: reading or writing one faults the CPU, which cannot be recovered from
```

And two warnings that catch the arithmetic this machine gets wrong quietly. `-Wall`
turns on more; `-Werror` makes them fatal.

```
warning: assigning 'word' to 'byte' truncates to 8 bits

warning: mixing signed and unsigned operands in '<'
    note: the result is treated as signed
```

At run time there are two more, printed on the text display before the machine halts.
`STACK OVERFLOW` comes from the guard a recursive program gets, because a call graph
with a cycle has no depth the compiler can compute. `HEAP CORRUPT` comes from `__free`
being handed something `__alloc` never returned.

---

## Things that bite

Every one of these has actually cost someone time.

- **`MUL` is 16×16 into 16 bits.** The top half is gone, with no warning.
  `1000 * 1000` is 16960. `cube.mona` uses a scale of 128 rather than 256 for exactly
  this reason.
- **There is no `MOD` instruction.** `a % b` becomes `a - (a / b) * b`, which is three
  instructions, not one.
- **Pointer subtraction is in bytes.** See above. It is the one place the language
  knowingly differs from C.
- **Nothing is bounds checked, and nothing clips.** A screen coordinate that leaves
  0..255 does not fault; it wraps into video memory and draws somewhere else.
- **Structs are packed.** `{ byte flag; word value; }` really is three bytes, with the
  word at an odd offset. That is fine — the machine has no alignment requirement at
  all — but `sizeof` will not match a C compiler's.
- **A big function may not compile at `-O0`.** An indirect operand's displacement is a
  signed byte, so a frame can address 64 slots, and unoptimized every intermediate
  takes one. `cube.mona` and the red-black tree in `src/test/resources/stress/` both
  need `-O1` to exist.
- **4,096 bytes is everything** — code, globals, strings, heap and stack. `--stats`
  reports what a program costs, and the compiler refuses one that does not fit rather
  than emitting it.
