package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.List;

/**
 * Runs the optimization passes to a fixpoint.
 *
 * <p>Iterating matters because the passes feed each other: strength reduction turns
 * a multiply into a copy, copy propagation forwards it, and dead code then deletes
 * both. A single sweep would leave most of that on the table.
 */
public final class PassManager {

    /** Enough rounds to settle; small functions converge in two or three. */
    private static final int MAX_ROUNDS = 8;

    private final List<Pass> passes;

    private PassManager(List<Pass> passes) {
        this.passes = passes;
    }

    public static PassManager forLevel(int optLevel) {
        if (optLevel <= 0) return new PassManager(List.of());
        return new PassManager(List.of(
                new PromoteLocals(),
                new ConstFold(),
                new StrengthReduce(),
                new CopyProp(),
                new DeadCode(),
                new BranchSimplify(),
                new LoopInvariantMotion()));
    }

    /**
     * @param wholeProgram whether the module is a complete program, so that anything
     *                     {@code main} cannot reach is genuinely dead. False under
     *                     {@code --no-entry}, where the functions are the deliverable.
     */
    public void run(Ir.Module module, boolean wholeProgram) {
        // Unreachable code first, so the per-function passes are not spent on
        // functions about to be deleted — and the deletion cascades on its own,
        // since a function is reachable only through a caller that survived.
        boolean prune = wholeProgram && !passes.isEmpty();
        if (prune) DeadSymbols.run(module);
        for (Ir.Function function : module.functions()) {
            run(function);
        }
        // And again afterwards, because folding a condition to a constant can leave
        // the only call to a function in a block that branch simplification removed.
        if (prune) DeadSymbols.run(module);
    }

    public void run(Ir.Function function) {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            boolean changed = false;
            for (Pass pass : passes) {
                changed |= pass.run(function);
            }
            if (!changed) return;
        }
    }
}
