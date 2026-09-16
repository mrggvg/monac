# Documentation

```
docs/
├── index.html          the guide, published at https://mrggvg.github.io/monac/
├── tour.md             a tour of the language and the compiler, in depth
├── building.md         building monac and running its tests
├── reference/
│   ├── language.md     the language in full: every form and every builtin
│   ├── machine.md      the target machine: calling convention, frames, traps
│   └── Mona.g4         the grammar
└── design/
    ├── optimization.md what the optimizer does, measured
    ├── coverage.md     what of the machine the compiler uses
    └── roadmap.md      done, not done, and declined
```

**Learning Mona?** Read [the guide](https://mrggvg.github.io/monac/) first, then work
through the [examples](../examples/). Keep [the language reference](reference/language.md)
open for the details.

**Curious how it works?** [The tour](tour.md) explains the design decisions, and
[optimization.md](design/optimization.md) measures each of them. [machine.md](reference/machine.md)
is what the compiler knows about the target, and [coverage.md](design/coverage.md) is what it
does not use.

**Working on the compiler?** Start with [building.md](building.md), and read
[the roadmap](design/roadmap.md) before starting on something new: it records what was
measured and declined, so it is not chased again.

The guide is a single HTML file with no build step. GitHub Pages serves this folder as
it stands, and `.nojekyll` stops it being run through Jekyll.
