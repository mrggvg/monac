package dev.madlador.opt;

import dev.madlador.ir.Ir;

/** One optimization over a single function. */
public interface Pass {

    String name();

    /** @return true if anything changed, so the manager knows to iterate */
    boolean run(Ir.Function function);
}
