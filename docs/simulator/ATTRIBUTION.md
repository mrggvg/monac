# The simulator in this directory

This is a copy of the **16-bit Assembly Simulator** that `monac` compiles for, published
here so that anyone reading the guide can run the compiler's output without hunting for
the simulator first. It is included **unmodified in behaviour** — the CPU, the assembler
and the peripherals are byte-for-byte as they were.

## What was changed

Only the plumbing needed to host it as static files:

- the pages were saved from a browser, so `16-bit Assembly Simulator.html` became
  `index.html` and its `_files/` directory became `assets/`;
- the two extension-less stylesheets became `fonts-source-code-pro.css` and
  `fonts-lato.css`;
- absolute links back into the course's Moodle installation were rewritten to the
  in-page anchors they were always meant to be, and the `<!-- saved from url= -->`
  comments were removed;
- each page gained a `lang` attribute, a description, a canonical link and a footer
  linking back to the Mona guide, so that the pages describe themselves to a search
  engine and a reader who lands on one can find the other;
- the instruction set page no longer sets `maximum-scale=0.9`, which prevented
  pinch-zoom on a phone.

No JavaScript was touched, and nothing the CPU, the assembler or the peripherals do
was changed.

## Where it comes from

The simulator descends from two pieces of earlier work:

1. **The original instruction set and simulator** by *Marco Schweighauser* (2015). A
   comment still in `assets/main.js` records the inheritance: "The original expression
   was defined by Marco Schweighauser."
2. **[asm-simulator](https://github.com/parraman/asm-simulator)** by *Pablo Parra*
   (University of Alcala), the Angular application this is built from, released under
   the MIT licence and reproduced in full below.

The version here is that application **as extended for the Sistemi I course at UP
FAMNIT** — the graphics card, the tile and bitmap video modes, the sprites, the keypad
and 4 KB of RAM are additions not present upstream. Those additions carry no licence
statement of their own. They are reproduced here for the students of that course; if you
hold rights in them and would rather this copy did not exist, open an issue and it will
be removed.

## Licence of the upstream application

```
MIT License

Copyright (c) 2017 Pablo Parra

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

The bundles also carry third-party libraries with their own notices, among them Angular,
CodeMirror (c) Marijn Haverbeke and others, and code (c) Microsoft Corporation.

`monac` itself is public domain under the Unlicence; that dedication covers the compiler,
not the simulator in this directory.
