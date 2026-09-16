#!/usr/bin/env node
/*
 * Proves the graphics card raises its own interrupt, and measures the rate.
 *
 *     java -cp target/classes dev.madlador.Main src/test/resources/probe/vsync.mona \
 *         -O1 -o /tmp/vsync.asm
 *     node src/test/resources/probe/vsync.js /tmp/vsync.asm
 *
 * WHY THIS IS NOT A JUNIT TEST. The card's refresh is an rxjs `timer(10, 20)`, so its
 * callback can only run when the JavaScript event loop turns. The oracle's run loop is
 * a tight synchronous `while (steps < maxSteps) cpu.step()`, which never yields — so
 * under the oracle this interrupt can never fire, and a program waiting on it spins
 * until maxSteps and reports a timeout. Nothing is wrong with either; they are simply
 * incompatible, and no amount of test plumbing fixes it.
 *
 * So this harness steps the CPU in short slices and hands the event loop back between
 * them. That makes it wall-clock dependent and therefore unfit to gate a build, which
 * is exactly why it lives here rather than in src/test/java. It is evidence, run by
 * hand, kept so the claim in language.md has something behind it.
 *
 * Because the yielding is coarse the measured rate undercounts a little: 48 Hz observed
 * against the 50 Hz the interval asks for.
 */
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '../../../..');
const MONASIM = path.join(ROOT, 'src/test/resources/oracle/monasim.js');

// monasim.js is a stdin/stdout driver, not a module. Load it with its trailing main()
// call stripped so its loader can be reused.
const source = fs.readFileSync(MONASIM, 'utf8').replace(
    /\nmain\(\);?\s*$/,
    '\nmodule.exports = { installShims, loadSimulator, findBundleDir, attachPeripherals };\n');
const Module = require('module');
const shim = new Module(MONASIM, null);
shim.filename = MONASIM;
shim.paths = Module._nodeModulePaths(path.dirname(MONASIM));
shim._compile(source, MONASIM);
const { installShims, loadSimulator, findBundleDir, attachPeripherals } = shim.exports;

const asmPath = process.argv[2];
if (!asmPath) {
    console.error('usage: node vsync.js <image.asm> [budget-ms]');
    process.exit(2);
}
const budgetMs = parseInt(process.argv[3] || '2000', 10);

installShims();
const sim = loadSimulator(findBundleDir());

(async () => {
    const assembled = new sim.AssemblerService().go(fs.readFileSync(asmPath, 'utf8'));
    const io = new sim.IORegMapService();
    const mem = new sim.MemoryService(io);
    const clk = new sim.ClockService();
    const cpu = new sim.CPUService(mem, clk, io);
    const peripherals = attachPeripherals(sim, io, cpu, clk);
    mem.storeBytes(0, assembled.code.length, assembled.code);

    const started = Date.now();
    let steps = 0;
    let halted = false;
    while (Date.now() - started < budgetMs) {
        // Short slices: a long one straddles several 20 ms ticks and loses them, since
        // a missed interval does not queue up twice.
        for (let i = 0; i < 1500; i++) {
            if ((cpu.SR.value & 1) !== 0) { halted = true; break; }
            cpu.step();
            steps++;
        }
        if (halted) break;
        await new Promise((resume) => setImmediate(resume));
    }
    const elapsed = Date.now() - started;
    for (const stop of peripherals.teardown) {
        try { stop(); } catch (ignored) { /* best effort */ }
    }

    const A = cpu.registersBank.get(0).value;
    console.log(`halted=${halted} A=${A} steps=${steps} wall=${elapsed}ms`);
    if (halted && A > 0) {
        console.log(`the card raised ${A} interrupts in ${elapsed}ms `
            + `(~${Math.round(1000 * A / elapsed)} Hz)`);
    } else {
        console.log('no refresh interrupt observed');
        process.exitCode = 1;
    }
    process.exit(process.exitCode || 0);
})();
