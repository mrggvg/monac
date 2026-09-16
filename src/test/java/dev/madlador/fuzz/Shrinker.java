package dev.madlador.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Cuts a failing program down to something a person can read.
 *
 * <p>This is the difference between a fuzzer someone uses and one everyone ignores. A
 * generated program that disagrees with the reference is typically forty lines of
 * nested arithmetic over a dozen names, and almost none of it is load-bearing — but
 * finding out which two lines matter by hand takes longer than the bug is worth.
 *
 * <p>The method is delta debugging: try a smaller program, keep it if it still fails,
 * repeat until nothing can be removed. Every candidate is checked against the same
 * predicate that found the failure, so a shrunk program is failing for the same reason
 * rather than for a new one.
 */
public final class Shrinker {

    /** How many full passes to make before settling for what is left. */
    private static final int ROUNDS = 6;

    /**
     * How long to spend, and how many candidates to try.
     *
     * <p>Both are needed, and finding that out cost a runaway. Every candidate is a
     * full compile plus a run on the simulator — order of a millisecond each, but the
     * passes restart from the beginning after any successful reduction, so the work is
     * quadratic in the program's size and unbounded in the worst case. Without a budget
     * the shrinker simply does not come back, which turns a test failure into a hung
     * build: strictly worse than an unshrunk report.
     *
     * <p>When the budget runs out the best program found so far is returned. A
     * partially shrunk report is still a far better bug report than the original.
     */
    private static final long BUDGET_MILLIS = 15_000;
    private static final int MAX_CANDIDATES = 4_000;

    private final Predicate<Prog.Program> stillFails;
    private long deadline;
    private int candidates;

    public Shrinker(Predicate<Prog.Program> stillFails) {
        this.stillFails = stillFails;
    }

    public Prog.Program shrink(Prog.Program program) {
        deadline = System.currentTimeMillis() + BUDGET_MILLIS;
        candidates = 0;

        Prog.Program best = program;
        for (int round = 0; round < ROUNDS && !exhausted(); round++) {
            Prog.Program before = best;
            best = dropFunctions(best);
            best = dropStatements(best);
            best = simplifyExpressions(best);
            if (Prog.print(best).equals(Prog.print(before))) break;   // a fixpoint
        }
        return best;
    }

    private boolean exhausted() {
        return candidates >= MAX_CANDIDATES || System.currentTimeMillis() > deadline;
    }

    /** Every candidate goes through here, so the budget cannot be sidestepped. */
    private boolean fails(Prog.Program candidate) {
        if (exhausted()) return false;
        candidates++;
        return stillFails.test(candidate);
    }

    /** Removes helper functions one at a time; {@code main} always stays. */
    private Prog.Program dropFunctions(Prog.Program program) {
        Prog.Program best = program;
        for (int i = 0; i < best.functions().size(); i++) {
            Prog.Func candidate = best.functions().get(i);
            if (candidate.name().equals("main")) continue;
            List<Prog.Func> remaining = new ArrayList<>(best.functions());
            remaining.remove(i);
            // Only legal if nothing calls it any more.
            Prog.Program trial = best.withFunctions(remaining);
            if (!calls(trial, candidate.name()) && fails(trial)) {
                best = trial;
                i--;
            }
        }
        return best;
    }

    private static boolean calls(Prog.Program program, String name) {
        return Prog.print(program).contains(name + "(");
    }

    /** Removes one statement at a time, anywhere in any function. */
    private Prog.Program dropStatements(Prog.Program program) {
        Prog.Program best = program;
        boolean changed = true;
        while (changed && !exhausted()) {
            changed = false;
            int total = count(best);
            for (int index = 0; index < total; index++) {
                Prog.Program trial = withoutStatement(best, index);
                if (trial != null && fails(trial)) {
                    best = trial;
                    changed = true;
                    break;
                }
            }
        }
        return best;
    }

    private static int count(Prog.Program program) {
        int total = 0;
        for (Prog.Func f : program.functions()) total += count(f.body());
        return total;
    }

    private static int count(List<Prog.Stmt> body) {
        int total = 0;
        for (Prog.Stmt s : body) {
            total++;
            total += switch (s) {
                case Prog.If i -> count(i.then()) + count(i.otherwise());
                case Prog.Loop l -> count(l.body());
                default -> 0;
            };
        }
        return total;
    }

    /** The program with the {@code index}-th statement removed, or null if it cannot be. */
    private static Prog.Program withoutStatement(Prog.Program program, int index) {
        int[] cursor = {0};
        List<Prog.Func> functions = new ArrayList<>();
        boolean[] removed = {false};
        for (Prog.Func f : program.functions()) {
            List<Prog.Stmt> body = removeAt(f.body(), index, cursor, removed);
            functions.add(new Prog.Func(f.name(), f.parameters(), body));
        }
        if (!removed[0]) return null;
        return program.withFunctions(functions);
    }

    private static List<Prog.Stmt> removeAt(List<Prog.Stmt> body, int index,
                                            int[] cursor, boolean[] removed) {
        List<Prog.Stmt> out = new ArrayList<>();
        for (Prog.Stmt s : body) {
            int here = cursor[0]++;
            if (here == index) {
                // A return cannot simply vanish from the end of a function, and a
                // declaration cannot go while anything still names it -- leave those
                // to the expression pass.
                if (s instanceof Prog.Return || s instanceof Prog.Declare
                        || s instanceof Prog.PointTo || s instanceof Prog.DeclareStruct
                        || s instanceof Prog.PointToStruct || s instanceof Prog.PointToField) {
                    out.add(s);
                } else {
                    removed[0] = true;
                }
                continue;
            }
            out.add(switch (s) {
                case Prog.If i -> new Prog.If(i.condition(),
                        removeAt(i.then(), index, cursor, removed),
                        removeAt(i.otherwise(), index, cursor, removed));
                case Prog.Loop l -> new Prog.Loop(l.counter(), l.times(),
                        removeAt(l.body(), index, cursor, removed));
                default -> s;
            });
        }
        return out;
    }

    /**
     * Collapses one subexpression at a time to a constant.
     *
     * <p>This is what actually makes the result readable: most of a generated
     * expression is padding around the one operator that matters, and replacing a
     * whole subtree with {@code 0} removes it in a single step where dropping
     * statements would not.
     */
    private Prog.Program simplifyExpressions(Prog.Program program) {
        Prog.Program best = program;
        boolean changed = true;
        while (changed && !exhausted()) {
            changed = false;
            int total = countExpressions(best);
            for (int index = 0; index < total; index++) {
                Prog.Program trial = collapse(best, index);
                if (trial != null && fails(trial)) {
                    best = trial;
                    changed = true;
                    break;
                }
            }
        }
        return best;
    }

    private static int countExpressions(Prog.Program program) {
        int[] total = {0};
        walk(program, e -> total[0]++);
        return total[0];
    }

    private static void walk(Prog.Program program, java.util.function.Consumer<Prog.Expr> visit) {
        for (Prog.Func f : program.functions()) walkBody(f.body(), visit);
    }

    private static void walkBody(List<Prog.Stmt> body,
                                 java.util.function.Consumer<Prog.Expr> visit) {
        for (Prog.Stmt s : body) {
            switch (s) {
                case Prog.Declare d -> walkExpr(d.value(), visit);
                case Prog.Assign a -> walkExpr(a.value(), visit);
                case Prog.AssignGlobal a -> walkExpr(a.value(), visit);
                case Prog.AssignIndex a -> {
                    walkExpr(a.index(), visit);
                    walkExpr(a.value(), visit);
                }
                case Prog.StoreThrough t -> walkExpr(t.value(), visit);
                case Prog.Return r -> walkExpr(r.value(), visit);
                case Prog.If i -> {
                    walkExpr(i.condition(), visit);
                    walkBody(i.then(), visit);
                    walkBody(i.otherwise(), visit);
                }
                case Prog.Loop l -> walkBody(l.body(), visit);
                case Prog.PointTo p -> { }
                case Prog.AssignField a -> {
                    walkField(a.field(), visit);
                    walkExpr(a.value(), visit);
                }
                case Prog.DeclareStruct d -> d.values().forEach(v -> walkExpr(v, visit));
                case Prog.PointToStruct p -> { }
                case Prog.PointToField p -> walkField(p.field(), visit);
                case Prog.Compound c -> walkExpr(c.value(), visit);
                case Prog.Step st -> { }
            }
        }
    }

    private static void walkExpr(Prog.Expr e, java.util.function.Consumer<Prog.Expr> visit) {
        visit.accept(e);
        switch (e) {
            case Prog.Bin b -> {
                walkExpr(b.left(), visit);
                walkExpr(b.right(), visit);
            }
            case Prog.Index i -> walkExpr(i.index(), visit);
            case Prog.Call c -> c.arguments().forEach(a -> walkExpr(a, visit));
            case Prog.Unary u -> walkExpr(u.operand(), visit);
            case Prog.Cast c -> walkExpr(c.operand(), visit);
            case Prog.Cond c -> {
                walkExpr(c.condition(), visit);
                walkExpr(c.then(), visit);
                walkExpr(c.otherwise(), visit);
            }
            case Prog.FieldRead f -> walkField(f.field(), visit);
            default -> { }
        }
    }

    private static void walkField(Prog.Field field, java.util.function.Consumer<Prog.Expr> visit) {
        if (field.struct() instanceof Prog.Element e) walkExpr(e.index(), visit);
        if (field.index() != null) walkExpr(field.index(), visit);
    }

    /** The program with the {@code index}-th expression replaced by 0. */
    private static Prog.Program collapse(Prog.Program program, int index) {
        int[] cursor = {0};
        boolean[] done = {false};
        List<Prog.Func> functions = new ArrayList<>();
        for (Prog.Func f : program.functions()) {
            functions.add(new Prog.Func(f.name(), f.parameters(),
                    mapBody(f.body(), index, cursor, done)));
        }
        return done[0] ? program.withFunctions(functions) : null;
    }

    private static List<Prog.Stmt> mapBody(List<Prog.Stmt> body, int index,
                                           int[] cursor, boolean[] done) {
        List<Prog.Stmt> out = new ArrayList<>();
        for (Prog.Stmt s : body) {
            out.add(switch (s) {
                case Prog.Declare d -> new Prog.Declare(d.name(), d.kind(), map(d.value(), index, cursor, done));
                case Prog.Assign a -> new Prog.Assign(a.name(), map(a.value(), index, cursor, done));
                case Prog.AssignGlobal a ->
                        new Prog.AssignGlobal(a.name(), map(a.value(), index, cursor, done));
                case Prog.AssignIndex a -> new Prog.AssignIndex(a.array(),
                        map(a.index(), index, cursor, done), map(a.value(), index, cursor, done));
                case Prog.StoreThrough t ->
                        new Prog.StoreThrough(t.pointer(), map(t.value(), index, cursor, done));
                case Prog.Return r -> new Prog.Return(map(r.value(), index, cursor, done));
                case Prog.If i -> new Prog.If(map(i.condition(), index, cursor, done),
                        mapBody(i.then(), index, cursor, done),
                        mapBody(i.otherwise(), index, cursor, done));
                case Prog.Loop l -> new Prog.Loop(l.counter(), l.times(),
                        mapBody(l.body(), index, cursor, done));
                case Prog.PointTo p -> p;
                case Prog.AssignField a -> {
                    Prog.Field field = mapField(a.field(), index, cursor, done);
                    yield new Prog.AssignField(field, map(a.value(), index, cursor, done));
                }
                case Prog.DeclareStruct d -> {
                    List<Prog.Expr> values = new ArrayList<>();
                    for (Prog.Expr v : d.values()) values.add(map(v, index, cursor, done));
                    yield new Prog.DeclareStruct(d.name(), d.struct(), values);
                }
                case Prog.PointToStruct p -> p;
                case Prog.PointToField p -> new Prog.PointToField(p.pointer(),
                        mapField(p.field(), index, cursor, done));
                case Prog.Compound c -> new Prog.Compound(c.name(), c.op(),
                        map(c.value(), index, cursor, done));
                case Prog.Step st -> st;
            });
        }
        return out;
    }

    private static Prog.Expr map(Prog.Expr e, int index, int[] cursor, boolean[] done) {
        int here = cursor[0]++;
        if (here == index) {
            done[0] = true;
            // Collapsing a bare constant achieves nothing, so decline and let the
            // pass move on to the next candidate.
            return e instanceof Prog.Const ? e : new Prog.Const(0);
        }
        return switch (e) {
            case Prog.Bin b -> new Prog.Bin(b.op(), map(b.left(), index, cursor, done),
                    map(b.right(), index, cursor, done));
            case Prog.Index i -> new Prog.Index(i.array(), map(i.index(), index, cursor, done));
            case Prog.Unary u -> new Prog.Unary(u.op(), map(u.operand(), index, cursor, done));
            case Prog.Cast c -> new Prog.Cast(c.kind(), map(c.operand(), index, cursor, done));
            case Prog.Cond c -> new Prog.Cond(map(c.condition(), index, cursor, done),
                    map(c.then(), index, cursor, done), map(c.otherwise(), index, cursor, done));
            case Prog.FieldRead f -> new Prog.FieldRead(mapField(f.field(), index, cursor, done));
            case Prog.Call c -> {
                List<Prog.Expr> arguments = new ArrayList<>();
                for (Prog.Expr a : c.arguments()) arguments.add(map(a, index, cursor, done));
                yield new Prog.Call(c.callee(), arguments);
            }
            default -> e;
        };
    }

    /** A field with its index expressions mapped, in the order {@link #walkField} visits them. */
    private static Prog.Field mapField(Prog.Field field, int index, int[] cursor, boolean[] done) {
        Prog.StructRef struct = field.struct() instanceof Prog.Element e
                ? new Prog.Element(e.array(), map(e.index(), index, cursor, done))
                : field.struct();
        Prog.Expr member = field.index() == null ? null : map(field.index(), index, cursor, done);
        return new Prog.Field(struct, field.member(), member);
    }
}
