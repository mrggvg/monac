package dev.madlador.opt;

import dev.madlador.ir.Ir;

import java.util.ArrayList;
import java.util.List;

/**
 * The values an instruction reads.
 *
 * <p>Shared by every pass that needs liveness, so that adding an IR instruction
 * means teaching exactly one place about it rather than four.
 */
public final class Uses {

    private Uses() {
    }

    public static List<Ir.Value> of(Ir.Instr instruction) {
        List<Ir.Value> result = new ArrayList<>(3);
        switch (instruction) {
            case Ir.Const ignored -> { }
            case Ir.Copy c -> result.add(c.src());
            case Ir.Bin b -> {
                result.add(b.lhs());
                result.add(b.rhs());
            }
            case Ir.Un u -> result.add(u.src());
            case Ir.Load l -> addAddress(l.addr(), result);
            case Ir.Store s -> {
                result.add(s.src());
                addAddress(s.addr(), result);
            }
            case Ir.Cmp c -> {
                result.add(c.lhs());
                result.add(c.rhs());
            }
            case Ir.Cbr c -> {
                result.add(c.lhs());
                result.add(c.rhs());
            }
            case Ir.Call c -> {
                result.addAll(c.args());
                if (c.callee() != null) result.add(c.callee());
            }
            case Ir.Ret r -> {
                if (r.value() != null) result.add(r.value());
            }
            case Ir.Br ignored -> { }
            case Ir.AddrOf a -> addAddress(a.addr(), result);
            case Ir.Flag ignored -> { }
            case Ir.ArgIn ignored -> { }   // it arrives; nothing computes it
            case Ir.TableBr t -> result.add(t.index());
            case Ir.In i -> result.add(i.port());
            case Ir.Out o -> {
                result.add(o.port());
                result.add(o.value());
            }
        }
        return result;
    }

    /** The register an instruction writes, or null. */
    public static Ir.VReg definitionOf(Ir.Instr instruction) {
        return switch (instruction) {
            case Ir.Const c -> c.dst();
            case Ir.Copy c -> c.dst();
            case Ir.Bin b -> b.dst();
            case Ir.Un u -> u.dst();
            case Ir.Load l -> l.dst();
            case Ir.Cmp c -> c.dst();
            case Ir.Call c -> c.dst();
            case Ir.AddrOf a -> a.dst();
            case Ir.In i -> i.dst();
            case Ir.Out ignored -> null;
            case Ir.Flag ignored -> null;
            case Ir.ArgIn a -> a.dst();
            case Ir.TableBr ignored -> null;
            case Ir.Store ignored -> null;
            case Ir.Cbr ignored -> null;
            case Ir.Br ignored -> null;
            case Ir.Ret ignored -> null;
        };
    }

    private static void addAddress(Ir.Addr addr, List<Ir.Value> into) {
        if (addr instanceof Ir.Addr.Mem mem) into.add(mem.base());
    }
}
