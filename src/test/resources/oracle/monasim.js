#!/usr/bin/env node
/*
 * monasim.js - headless driver for the "16-bit Assembly Simulator".
 *
 * This is a GOLDEN ORACLE, not a reimplementation: it loads the simulator's own
 * webpack bundle (assembler.service.ts + cpu.service.ts + memory.service.ts) and
 * runs the real assembler and the real CPU. Any behaviour it reports is the
 * behaviour the browser simulator would produce.
 *
 * Protocol: newline-delimited JSON on stdin, one response line per request.
 *
 *   ->  {"asm": "MOV A, 5\nHLT", "maxSteps": 10000, "dumpMem": [4096, 4128]}
 *   ->  {"asm": "...", "assembleOnly": true}          -> {"ok":true, "size":57}
 *   <-  {"ok":true, "steps":2, "regs":{"A":5,...}, "sr":1, "halted":true, "mem":[...]}
 *   <-  {"ok":false, "phase":"assemble", "error":"...", "line":3}
 *
 * The bundle is found by searching upward for asm-sim/, or via $MONAC_SIMULATOR.
 */
'use strict';
const fs = require('fs');
const path = require('path');
const readline = require('readline');

/* ---------- locate the simulator bundle ---------- */

const BUNDLE_SUBPATH = path.join('asm-sim', '16-bit Assembly Simulator_files');

function findBundleDir() {
    if (process.env.MONAC_SIMULATOR) {
        const p = process.env.MONAC_SIMULATOR;
        if (fs.existsSync(path.join(p, 'main.js'))) return p;
        if (fs.existsSync(path.join(p, BUNDLE_SUBPATH, 'main.js'))) return path.join(p, BUNDLE_SUBPATH);
        throw new Error('MONAC_SIMULATOR set but no main.js found under ' + p);
    }
    let dir = __dirname;
    for (let i = 0; i < 12; i++) {
        const cand = path.join(dir, BUNDLE_SUBPATH);
        if (fs.existsSync(path.join(cand, 'main.js'))) return cand;
        const parent = path.dirname(dir);
        if (parent === dir) break;
        dir = parent;
    }
    throw new Error('could not locate "' + BUNDLE_SUBPATH + '" searching upward from ' + __dirname +
                    ' (set $MONAC_SIMULATOR to the asm-sim directory)');
}

/* ---------- browser shims ----------
 * The bundle is an Angular app. None of the ISA modules touch the DOM, but the
 * bundle's top level does, so we stub just enough for it to evaluate. */

function installShims() {
    global.window = global;
    global.self = global;
    const noopClass = () => function () {};
    for (const n of ['MouseEvent', 'KeyboardEvent', 'Event', 'Node', 'Element', 'HTMLElement',
                     'XMLHttpRequest', 'MutationObserver', 'CustomEvent', 'Touch']) {
        if (!global[n]) global[n] = noopClass();
    }
    global.document = {
        createElement: () => ({ style: {}, setAttribute() {}, appendChild() {}, getContext: () => ({}) }),
        addEventListener() {}, removeEventListener() {},
        documentElement: { style: {} }, body: { appendChild() {} },
        querySelector: () => null, head: { appendChild() {} }, createTextNode: () => ({})
    };
    // Node >= 21 defines `navigator` as a getter-only global; plain assignment
    // throws under 'use strict'. Only define it if the runtime lacks one.
    if (!global.navigator) {
        Object.defineProperty(global, 'navigator', {
            value: { userAgent: 'node' }, writable: true, configurable: true
        });
    }
    global.getComputedStyle = () => ({});
}

/* ---------- load the bundle and build a minimal webpack require ---------- */

function loadSimulator(bundleDir) {
    const captured = [];
    const jsonpArray = [];
    jsonpArray.push = function (chunk) {
        captured.push(chunk);
        return Array.prototype.push.call(this, chunk);
    };
    global.webpackJsonp = jsonpArray;

    // polyfills.js needs a real browser; the ISA modules do not depend on it.
    for (const f of ['vendor.js', 'main.js']) {
        require(path.join(bundleDir, f));
    }

    const modules = {};
    for (const chunk of captured) if (chunk[1]) Object.assign(modules, chunk[1]);
    if (Object.keys(modules).length === 0) throw new Error('no webpack modules captured from bundle');

    const cache = {};
    function wr(id) {
        if (cache[id]) return cache[id].exports;
        if (!modules[id]) throw new Error('missing module: ' + id);
        const m = cache[id] = { i: id, l: false, exports: {} };
        modules[id].call(m.exports, m, m.exports, wr);
        m.l = true;
        return m.exports;
    }
    wr.r = (e) => { Object.defineProperty(e, '__esModule', { value: true }); };
    wr.d = (e, n, g) => { if (!wr.o(e, n)) Object.defineProperty(e, n, { enumerable: true, get: g }); };
    wr.o = (o, p) => Object.prototype.hasOwnProperty.call(o, p);
    wr.n = (m) => { const g = m && m.__esModule ? () => m['default'] : () => m; wr.d(g, 'a', g); return g; };
    wr.t = (v) => v;

    // cpu.service.ts MUST load first: its @Instruction decorators populate the
    // instruction table the assembler consults. Without it every mnemonic is
    // reported as "Invalid instruction".
    wr('./src/app/cpu.service.ts');

    // The peripherals are Angular components, but their constructors take only
    // services — no DOM — so they can be driven headlessly like everything else.
    const optional = (id, name) => {
        try {
            const module = wr(id);
            return module[name] || Object.values(module)[0] || null;
        } catch (e) {
            return null;
        }
    };

    return {
        AssemblerService:  wr('./src/app/assembler.service.ts').AssemblerService,
        MemoryService:     wr('./src/app/memory.service.ts').MemoryService,
        ClockService:      wr('./src/app/clock.service.ts').ClockService,
        IORegMapService:   wr('./src/app/ioregmap.service.ts').IORegMapService,
        CPUService:        wr('./src/app/cpu.service.ts').CPUService,
        IrqCtrlService:    optional('./src/app/irqctrl.service.ts', 'IrqCtrlService'),
        GraphicsCard:      optional('./src/app/graphics-card/graphics-card.component.ts',
                                    'GraphicsCardComponent'),
        Keypad:            optional('./src/app/keypad/keypad.component.ts', 'KeypadComponent'),
        RndGenService:     optional('./src/app/rndgen.service.ts', 'RndGenService'),
        TimerService:      optional('./src/app/timer.service.ts', 'TimerService')
    };
}

/**
 * Attaches the peripherals that live on I/O ports: graphics (7, 8, 9), keypad
 * (5, 6), timer (3, 4) and the random generator (10).
 *
 * <p>Without these, any IN or OUT to those ports faults with "Invalid register
 * address", because the ports are registered by the components rather than by the
 * core services.
 */
function attachPeripherals(sim, io, cpu, clk) {
    const attached = { graphics: null, keypad: null, teardown: [] };
    if (!sim.IrqCtrlService) return attached;

    const irq = new sim.IrqCtrlService(io, cpu);

    if (sim.GraphicsCard) {
        const gfx = new sim.GraphicsCard(io, irq);
        if (typeof gfx.ngOnInit === 'function') gfx.ngOnInit();
        // ngAfterViewInit would build these from a real canvas. Nothing on the ISA
        // side reads them; the card only writes pixels into the buffer.
        gfx.imageBuffer = { data: new Uint8ClampedArray(256 * 256 * 4) };
        gfx.context = { putImageData() {}, createImageData: () => gfx.imageBuffer };
        // ngAfterViewInit would also call reset(), and that is not cosmetic: it is
        // what fills the 64 KB of video memory with zeroes and loads the tile ROM and
        // the palette. Without it memoryCells is a sparse array of undefined, and any
        // repaint — which VIDMODE 2 and VIDMODE 3 both trigger — looks up
        // PALETTE[undefined] and throws part way through the run.
        if (typeof gfx.reset === 'function') gfx.reset();
        attached.graphics = gfx;
        // The card runs a 50 Hz refresh; left running it would keep node alive.
        attached.teardown.push(() => {
            if (typeof gfx.stopRefreshTimer === 'function') gfx.stopRefreshTimer();
        });
    }
    if (sim.Keypad) {
        const keypad = new sim.Keypad(io, irq);
        if (typeof keypad.ngOnInit === 'function') keypad.ngOnInit();
        attached.keypad = keypad;
    }
    if (sim.RndGenService) new sim.RndGenService(io);
    if (sim.TimerService) new sim.TimerService(io, irq, clk);

    return attached;
}

/* ---------- register indices (from cpuregs.ts CPURegisterIndex) ---------- */
const REGS = { A: 0, B: 1, C: 2, D: 3, SP: 4 };
const SR_HALT = 1 << 0;
const SR_FAULT = 1 << 1;

function runOne(sim, req) {
    const maxSteps = (typeof req.maxSteps === 'number' && req.maxSteps > 0) ? req.maxSteps : 100000;

    let assembled;
    try {
        assembled = new sim.AssemblerService().go(req.asm);
    } catch (e) {
        return { ok: false, phase: 'assemble',
                 error: String(e && (e.error || e.message) || e),
                 line: (e && e.line) || null };
    }

    // Fresh machine per request: no state can leak between test cases.
    const io = new sim.IORegMapService();
    const mem = new sim.MemoryService(io);
    const clk = new sim.ClockService();
    const cpu = new sim.CPUService(mem, clk, io);

    // Peripherals are opt-in: most programs do not need them, and attaching the
    // graphics card starts a refresh timer that has to be torn down afterwards.
    const wantPeripherals = req.peripherals === true
        || Array.isArray(req.dumpVram) || Array.isArray(req.dumpScreen)
        || typeof req.keys === 'string';
    const peripherals = wantPeripherals
        ? attachPeripherals(sim, io, cpu, clk)
        : { graphics: null, keypad: null, teardown: [] };

    // Keystrokes. processKey takes the character as a STRING plus a status code
    // (bit 0 = key down). They are delivered DURING the run rather than before it:
    // a keyboard interrupt is only raised once the program has set the device's bit
    // in IRQMASK, which has not happened yet at step zero.
    const keys = typeof req.keys === 'string' ? req.keys : '';
    const keyEvery = (typeof req.keyEvery === 'number' && req.keyEvery > 0)
        ? req.keyEvery : 1000;
    let keysSent = 0;
    // A real keypress is TWO events: 1 as the key goes down, 2 as it comes up, and
    // the keypad raises its line for both. Sending only key-downs is what let a bug
    // ship in snake.mona -- polling code that tests KBDSTATUS for 1 and returns early
    // never drains KBDDATA on the release, the port latches, and every later press
    // reads 5 rather than 1. Nothing in the repo could see it.
    //
    // `keyUp: true` sends the release as well, which is what the hardware does. It is
    // opt-in rather than the default because existing tests count handler entries, and
    // doubling the events would change what they mean rather than what they check.
    const keyUp = req.keyUp === true;
    let keyPhase = 0;
    const sendKey = () => {
        if (keysSent >= keys.length || !peripherals.keypad) return;
        if (typeof peripherals.keypad.processKey === 'function') {
            peripherals.keypad.processKey(keys[keysSent], keyUp && keyPhase === 1 ? 2 : 1);
        }
        if (keyUp && keyPhase === 0) {
            keyPhase = 1;               // the same key comes back up next time
        } else {
            keyPhase = 0;
            keysSent++;
        }
    };

    const code = assembled.code;

    // Assemble-only: how big the image is, which is answerable for programs that
    // cannot run headlessly at all — anything that faults on a port, say.
    if (req.assembleOnly === true) {
        for (const stop of peripherals.teardown) {
            try { stop(); } catch (ignored) { /* best effort */ }
        }
        return { ok: true, size: code.length, steps: 0, halted: false, timedOut: false,
                 fault: false, regs: {}, sr: 0 };
    }

    try {
        mem.storeBytes(0, code.length, code);
    } catch (e) {
        return { ok: false, phase: 'load',
                 error: 'image too large (' + code.length + ' bytes): ' + String(e && e.message || e) };
    }

    let steps = 0;
    let halted = false;
    try {
        while (steps < maxSteps) {
            if ((cpu.SR.value & SR_HALT) !== 0) { halted = true; break; }
            if (keysSent < keys.length && steps > 0 && steps % keyEvery === 0) sendKey();
            cpu.step();
            steps++;
        }
        if (!halted && (cpu.SR.value & SR_HALT) !== 0) halted = true;
    } catch (e) {
        for (const stop of peripherals.teardown) {
            try { stop(); } catch (ignored) { /* best effort */ }
        }
        return { ok: false, phase: 'run', steps: steps,
                 error: String(e && e.message || e),
                 sr: cpu.SR.value, ip: cpu.IP.value };
    }

    const regs = {};
    for (const name of Object.keys(REGS)) regs[name] = cpu.registersBank.get(REGS[name]).value;

    const res = {
        ok: true,
        steps: steps,
        halted: halted,
        timedOut: !halted,
        fault: (cpu.SR.value & SR_FAULT) !== 0,
        regs: regs,
        ip: cpu.IP.value,
        sr: cpu.SR.value,
        size: code.length
    };

    if (Array.isArray(req.dumpVram) && req.dumpVram.length === 2 && peripherals.graphics) {
        const [start, end] = req.dumpVram;
        const cells = peripherals.graphics.memoryCells || [];
        const bytes = [];
        for (let a = start; a < end; a++) bytes.push(cells[a] === undefined ? 0 : cells[a]);
        res.vram = bytes;
    }

    // The rendered canvas, as opposed to video memory. In bitmap mode the two are the
    // same thing modulo the palette, but in tile mode they are not related at all: the
    // card composes 17x17 tiles, a scroll offset, a background colour and eight
    // sprites into these pixels, and reading the tile map back tells you nothing about
    // whether any of that worked. A rectangle rather than the whole screen, because
    // 65,536 pixels is half a megabyte of JSON and a test wants a handful.
    if (Array.isArray(req.dumpScreen) && req.dumpScreen.length === 4 && peripherals.graphics) {
        const [sx, sy, sw, sh] = req.dumpScreen;
        const data = (peripherals.graphics.imageBuffer || {}).data;
        const out = [];
        for (let y = sy; y < sy + sh; y++) {
            for (let x = sx; x < sx + sw; x++) {
                if (!data || x < 0 || x > 255 || y < 0 || y > 255) { out.push(0); continue; }
                const i = 4 * (256 * y + x);
                out.push((data[i] << 16) | (data[i + 1] << 8) | data[i + 2]);
            }
        }
        res.screen = out;
    }

    for (const stop of peripherals.teardown) {
        try { stop(); } catch (e) { /* best effort */ }
    }

    if (Array.isArray(req.dumpMem) && req.dumpMem.length === 2) {
        const start = req.dumpMem[0], end = req.dumpMem[1];
        const bytes = [];
        for (let a = start; a < end; a++) {
            try { bytes.push(mem.loadByte(a)); } catch (e) { bytes.push(null); }
        }
        res.mem = bytes;
    }
    return res;
}

/* ---------- main ---------- */

function main() {
    installShims();
    let sim;
    try {
        sim = loadSimulator(findBundleDir());
    } catch (e) {
        process.stdout.write(JSON.stringify({ ok: false, phase: 'init', error: String(e.message || e) }) + '\n');
        process.exit(2);
    }

    // Single-shot mode: monasim.js <file.asm>
    const fileArg = process.argv[2];
    if (fileArg) {
        const asm = fs.readFileSync(fileArg, 'utf8');
        process.stdout.write(JSON.stringify(runOne(sim, { asm: asm })) + '\n');
        return;
    }

    process.stdout.write(JSON.stringify({ ok: true, phase: 'ready' }) + '\n');
    const rl = readline.createInterface({ input: process.stdin, terminal: false });
    rl.on('line', (line) => {
        if (!line.trim()) return;
        let out;
        try {
            out = runOne(sim, JSON.parse(line));
        } catch (e) {
            out = { ok: false, phase: 'protocol', error: String(e && e.message || e) };
        }
        process.stdout.write(JSON.stringify(out) + '\n');
    });
    rl.on('close', () => process.exit(0));
}

main();
