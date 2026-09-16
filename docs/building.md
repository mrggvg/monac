# Building monac

## Requirements

- **A JDK 21 or newer** — a JDK, not a JRE. The compiler targets `release 21`.
- **Maven 3.9+**
- **Node.js** (optional) — only for the end-to-end tests, which drive the real simulator.

## Commands

```bash
mvn test              # unit tests only; never touches Node
mvn -Pe2e verify      # adds the end-to-end suite, executed on the real simulator
mvn package           # builds target/monac.jar
```

662 tests: 279 unit and 383 end to end. The end-to-end ones are the interesting half —
they assemble and execute on the simulator itself, and every program under
`src/test/resources/e2e/` is run at both `-O0` and `-O1` with the two results required
to agree.

Some suites are parameterised over something other than the optimization level:
`HeapIT` runs its whole contract under each of the three allocators, because three
implementations of one contract is exactly the situation where the least-used one
rots quietly.

### The stress programs

`src/test/resources/stress/` holds programs that are too heavy for the corpus: a
binary search tree with the full three-case delete, a pairing heap, and a red-black
tree that checks all four of its invariants after every fill. Each one fills the heap
until the allocator refuses, empties it, and does that again — millions of
instructions apiece, which is why they are not run at every `mvn verify` alongside
programs that take a thousand.

`HeapStressIT` runs each of them under every allocator and both optimization levels.
They are the heaviest pointer code here as well as the heaviest allocation: a
red-black rotation rewrites six links through five struct members, and both wrong-code
bugs this compiler has had were stores through a pointer into a struct member.

The red-black tree is skipped at `-O0`, and the skip is the point rather than a
nuisance: unoptimized it is 5,993 bytes against 3,132 optimized, so the fit check
correctly refuses it. `cube.mona` is in the same position.

Each program seeds a small generator from a global called `rng`, so a longer or wider
run is a matter of substituting seeds — which is what the soak in the commit that
added them did, over thirty of them.

```bash
java -jar target/monac.jar program.mona -o program.asm
java -jar target/monac.jar program.mona --dump-ast
java -jar target/monac.jar program.mona --stats -o /dev/null   # instructions and bytes
java -jar target/monac.jar program.mona --heap-strategy bitmap -o out.asm
```

`--heap-strategy bump|slab|bitmap|auto` forces which allocator is emitted rather than
letting the compiler choose from the program. It exists for the tests, and for reading
the difference: a program that cannot meet a tier's precondition quietly gets the
general allocator instead of failing.

### Running one test

Unit tests take a class name. The end-to-end ones are integration tests, so they need
the `e2e` profile and a different property — and surefire has to be told not to fail
when no *unit* test matches the name:

```bash
mvn test -Dtest=StructTest
mvn -Pe2e verify -Dit.test=StructIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false
```

Each `.mona` file under `src/test/resources/e2e/` declares what it expects in a
comment, so adding a case is adding a file:

```c
// expect: A=21
// display: HELLO MONA
// maxSteps: 200000
```

The worked programs in [../examples/](../examples/) are covered too: several tests read them
off disk and run them, so an example that stops working fails the build rather than
sitting there wrong.

## If you see `error: release version 21 not supported`

That message means Maven is running on a JVM with no compiler in it — typically a
system where `java` resolves to a JRE while a usable `javac` sits elsewhere on the
PATH. It is a confusing way to say "there is no compiler here".

**The build handles this itself.** `pom.xml` carries a profile that activates only
when the running JVM has no `javac` of its own, and forks to whichever one is on the
PATH. Where `JAVA_HOME` is a full JDK the profile never activates and nothing changes.

If you still hit it, there is no `javac` on the PATH either. Point `JAVA_HOME` at a
real JDK:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 mvn package
```

To check what Maven is running on:

```bash
mvn -v                      # the "runtime:" line
ls $(mvn -v | grep -oP '(?<=runtime: ).*')/bin/javac
```

## The end-to-end oracle

`src/test/resources/oracle/monasim.js` runs the simulator's **own** assembler and CPU
headlessly under Node, by loading its webpack bundle with a small set of browser shims.
Tests therefore assert against the real target machine rather than against a
reimplementation of it — see `src/test/java/dev/madlador/oracle/MachineContractIT.java`,
which pins every machine fact the code generator depends on.

The script finds the simulator by searching upward for
`asm-sim/16-bit Assembly Simulator_files/`. Override with `$MONAC_SIMULATOR`.
Choose a different Node binary with `-Dmonac.node=<path>` or `$MONAC_NODE`.

If Node is unavailable the end-to-end tests **skip** rather than fail, so the repository
still builds on a bare machine.

Talk to it by hand:

```bash
echo '{"asm":"MOV A, 7\nMOV B, 6\nMUL B\nHLT"}' | node src/test/resources/oracle/monasim.js
node src/test/resources/oracle/monasim.js program.asm
```
