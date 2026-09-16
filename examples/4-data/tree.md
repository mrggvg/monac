# The tree, in detail

← [All examples](../README.md) · the program: [tree.mona](tree.mona)

[`tree.mona`](tree.mona) puts ten numbers into a binary search tree and walks it onto
the display. They come out sorted, because an in-order walk of a search tree *is* a
sort — the algorithm is the shape of the data.

```
04 11 17 23 29 35 42 56 68 91
```

It is here because it needs two things this machine makes awkward — a type that
refers to itself, and recursion deep enough to matter — and because it shows how
little a program of this size needs the heap.

## A type that contains itself

```c
struct Node {
    word value;
    struct Node* left;
    struct Node* right;
};
```

`struct Node*` and not `struct Node`. A struct that contained itself by value would
have no size — it would be its own size plus two more of them, forever — and the
compiler says exactly that:

```
error: 'struct Bad' contains itself, so it has no size
    note: hold it by pointer instead: 'struct Bad* next;'
```

Through a pointer it is two bytes whether or not the thing it points at has been
measured yet, which is why the layout pass can leave the members of `Node` unresolved,
work out everything else, and come back. It runs to a fixpoint for that reason.

## A pool, not the heap

```c
const word CAPACITY = 16;
struct Node pool[CAPACITY];
word used = 0;

struct Node* newNode(word value) {
    if (used == CAPACITY) return 0;
    struct Node* node = &pool[used++];
    node->value = value;
    return node;
}
```

The program knows it will never need more than ten nodes, so it declares sixteen and
hands them out in order. Against `__alloc` that is no allocator in the image, no
bookkeeping per block, nothing that can run out at an unpredictable moment — and the
pool's 96 bytes are in `--stats` before the program ever runs. RAM arrives zeroed and
a node is never handed out twice, so both links start out null without a line of
code. Written with `__alloc`, as it was until the examples were rewritten, the same
program was 791 bytes and executed 1,907 instructions; the pool makes it 750 and
1,768.

## The pointer to the link

```c
struct Node** link = &root;
while (*link != 0) {
    struct Node* at = *link;
    link = value < at->value ? &at->left : &at->right;
}
*link = node;
```

The obvious insertion walks the nodes and has to remember which side of which parent
the new one goes on, and treats the empty tree as a case of its own. Walking the
*links* instead — `link` points at the pointer that will hold the new node — needs
neither: the first link is `root` itself. `&at->left` is the address of a member
through a pointer, which is the pointer plus the member's offset.

## Recursion that has to balance

```c
void show(struct Node* node) {
    if (node == 0) return;
    show(node->left);
    SCREEN[column]     = '0' + node->value / 10 % 10;
    SCREEN[column + 1] = '0' + node->value % 10;
    SCREEN[column + 2] = ' ';
    column += 3;
    show(node->right);
}
```

Two recursive calls per node, and the stack grows down from `0x0FFF` toward the code
with nothing watching for a collision. Ten nodes at a height of four is nowhere near
it — but the test asserts `SP` is back at `0x0FFF` afterwards, because a calling
convention that leaks two bytes per call is invisible until it is not.

## What the compiler makes of it

```
241 instructions, 750 bytes of 4096
```

Some of those are the stack guard: `tree` recurses, so the compiler cannot bound its
depth and puts a two-instruction check at the top of `height` and `show`, plus the
routine that writes `STACK OVERFLOW` if either one fires.

`node->left` is one load and one displacement: the member's offset is known at compile
time, so reaching through a pointer costs the same as reaching through any pointer.
A member of a *local* struct costs less still — the offset folds into the frame
displacement, so `p.y` is a single instruction, the same as a plain local.

## Things to try

- Delete a node. It is the one operation on a search tree that is genuinely fiddly,
  and the three cases (no children, one child, two) are a good argument for drawing
  the pointers on paper first. With a pool, a deleted node wants a free list: chain
  the spare nodes through their `left` links and have `newNode` take from it first.
- Balance it. Inserting 1..10 in order gives a tree of height 10 — a linked list with
  extra steps — and `height()` is already there to prove it.
- Count the nodes and the comparisons, and put both on the display next to the sorted
  values. The display is 32 characters and the numbers take 30.
- Store strings instead of numbers. A `byte*` member and the string literals already
  work; the comparison becomes a loop.
