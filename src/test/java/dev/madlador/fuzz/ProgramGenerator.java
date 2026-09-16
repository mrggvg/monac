package dev.madlador.fuzz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;

/**
 * Random whole programs, built so that every one of them is legal, terminating and
 * deterministic.
 *
 * <p>{@code ExpressionFuzzIT} generates expressions over constants, which is the highest
 * bug-per-line test in the project and still never emits a variable, a branch, a call or
 * a pointer. Both of the wrong-code bugs this project has actually shipped lived in
 * exactly those: {@code &} of a struct member returned zero, and a store through a
 * spilled pointer clobbered a live register. This is the generator that would have found
 * them.
 *
 * <p>Three properties are guaranteed by construction rather than checked afterwards,
 * because a fuzzer that produces programs which do not compile, do not stop, or do not
 * have one right answer wastes the run:
 *
 * <ul>
 *   <li><b>It terminates.</b> The only loop is counted, and its counter is emitted by
 *       the printer, so nothing generated can touch it.</li>
 *   <li><b>It has no faults.</b> Divisors are forced odd and shift counts masked below
 *       16, so no division by zero and no meaningless shift.</li>
 *   <li><b>It compiles.</b> Names are only used where they are in scope, and a function
 *       may only call ones declared after it, so the call graph is acyclic.</li>
 * </ul>
 */
public final class ProgramGenerator {

    /**
     * How adventurous to be. Pointer-heavy shapes, structs and narrow integers are
     * where the bugs have been.
     */
    public enum Profile { GENERAL, POINTER_HEAVY, STRUCTS_AND_BYTES }

    private final Random random;
    private final Profile profile;
    private int nextName;
    private final List<Prog.StructType> structs = new ArrayList<>();
    private final List<Prog.StructVar> structGlobals = new ArrayList<>();

    public ProgramGenerator(Random random, Profile profile) {
        this.random = random;
        this.profile = profile;
    }

    private String fresh(String prefix) {
        return prefix + (nextName++);
    }

    /** What a statement may refer to at the point it is generated. */
    private static final class Scope {
        /** Names that may be read AND written, with their types. */
        final List<String> locals = new ArrayList<>();
        final Map<String, Prog.Kind> kinds = new HashMap<>();
        /**
         * Loop counters: readable, never assignable, never addressable.
         *
         * <p>This list is the whole of the termination guarantee. The counter is
         * emitted by the printer, but adding it to {@link #locals} would let a
         * generated statement assign to it or take its address and store through it,
         * and the loop would stop terminating. Which is exactly what happened: the
         * first run of this fuzzer produced two programs that never halted.
         */
        final List<String> readOnly = new ArrayList<>();
        /** Pointers to a word: a word local or a word member. */
        final List<String> pointers = new ArrayList<>();
        /** Struct locals and struct pointers, by the name of their struct type. */
        final Map<String, String> structLocals = new HashMap<>();
        final Map<String, String> structPointers = new HashMap<>();
        final List<Prog.Global> globals;
        /** Functions this one may call: those declared after it. */
        final List<Prog.Func> callable;

        Scope(List<Prog.Global> globals, List<Prog.Func> callable) {
            this.globals = globals;
            this.callable = callable;
        }

        Scope child() {
            Scope inner = new Scope(globals, callable);
            inner.locals.addAll(locals);
            inner.kinds.putAll(kinds);
            inner.readOnly.addAll(readOnly);
            inner.pointers.addAll(pointers);
            inner.structLocals.putAll(structLocals);
            inner.structPointers.putAll(structPointers);
            return inner;
        }

        /** Everything readable, which is what an expression may name. */
        List<String> readable() {
            List<String> all = new ArrayList<>(locals);
            all.addAll(readOnly);
            return all;
        }

        /** Locals a word* may point at. */
        List<String> wordLocals() {
            List<String> words = new ArrayList<>();
            for (String name : locals) {
                if (kinds.getOrDefault(name, Prog.Kind.WORD) == Prog.Kind.WORD) words.add(name);
            }
            return words;
        }
    }

    public Prog.Program generate() {
        boolean withStructs = profile == Profile.STRUCTS_AND_BYTES || random.nextInt(4) == 0;
        if (withStructs) {
            int count = 1 + random.nextInt(2);
            for (int i = 0; i < count; i++) {
                List<Prog.Member> members = new ArrayList<>();
                int memberCount = 2 + random.nextInt(3);
                for (int m = 0; m < memberCount; m++) {
                    // Now and then an array member, a power of two long so masking is exact.
                    int length = random.nextInt(4) == 0 ? (2 << random.nextInt(2)) : 0;
                    members.add(new Prog.Member("m" + m, kind(), length));
                }
                structs.add(new Prog.StructType(fresh("S"), members));
            }
            for (Prog.StructType type : structs) {
                if (random.nextBoolean()) {
                    int count2 = random.nextBoolean() ? 0 : (2 << random.nextInt(2));
                    structGlobals.add(new Prog.StructVar(fresh("t"), type.name(), count2));
                }
            }
        }

        List<Prog.Global> globals = new ArrayList<>();
        int globalCount = 1 + random.nextInt(3);
        for (int i = 0; i < globalCount; i++) {
            // A scalar or a small array; the array sizes are powers of two so the
            // index mask below is exact.
            int size = random.nextInt(3) == 0 ? (4 << random.nextInt(2)) : 0;
            globals.add(new Prog.Global(fresh("g"), kind(), size));
        }

        // Built back to front so each function can only call ones already made, which
        // is what keeps the call graph acyclic and the recursion depth finite.
        List<Prog.Func> functions = new ArrayList<>();
        int helpers = random.nextInt(3);
        for (int i = 0; i < helpers; i++) {
            List<String> parameters = new ArrayList<>();
            int arity = random.nextInt(3);
            for (int p = 0; p < arity; p++) parameters.add(fresh("p"));
            Scope scope = new Scope(globals, new ArrayList<>(functions));
            scope.locals.addAll(parameters);
            List<Prog.Stmt> body = block(scope, 2, true);
            functions.add(new Prog.Func(fresh("f"), parameters, body));
        }

        Scope main = new Scope(globals, new ArrayList<>(functions));
        functions.add(new Prog.Func("main", List.of(), block(main, 3, true)));
        return new Prog.Program(structs, globals, structGlobals, functions);
    }

    /** Mostly words; narrow and signed types often enough to matter. */
    private Prog.Kind kind() {
        int roll = random.nextInt(profile == Profile.STRUCTS_AND_BYTES ? 4 : 8);
        return switch (roll) {
            case 1 -> Prog.Kind.BYTE;
            case 2 -> Prog.Kind.SBYTE;
            case 3 -> Prog.Kind.SWORD;
            default -> Prog.Kind.WORD;
        };
    }

    /** A block of statements, ending in a return when one is required. */
    private List<Prog.Stmt> block(Scope scope, int depth, boolean mustReturn) {
        List<Prog.Stmt> body = new ArrayList<>();
        int count = 1 + random.nextInt(4);
        for (int i = 0; i < count; i++) body.add(statement(scope, depth));
        if (mustReturn) body.add(new Prog.Return(expression(scope, 2)));
        return body;
    }

    private Prog.Stmt statement(Scope scope, int depth) {
        int roll = random.nextInt(100);
        int pointerShare = profile == Profile.POINTER_HEAVY ? 10 : 3;

        if (scope.locals.isEmpty() || roll < 10) return declare(scope);
        if (roll < 20) return new Prog.Assign(pick(scope.locals), expression(scope, 2));
        if (roll < 27) {
            Prog.Global g = pick(scope.globals);
            if (g.size() > 0) {
                return new Prog.AssignIndex(g.name(), masked(expression(scope, 1), g.size()),
                        expression(scope, 1));
            }
            return new Prog.AssignGlobal(g.name(), expression(scope, 2));
        }
        if (roll < 34 && depth > 0) {
            // A branch. Each arm gets its own scope, because a declaration inside one
            // is not visible after it -- in Mona or here.
            Scope thenScope = scope.child();
            Scope elseScope = scope.child();
            List<Prog.Stmt> otherwise = random.nextBoolean()
                    ? block(elseScope, depth - 1, false) : List.of();
            return new Prog.If(expression(scope, 1), block(thenScope, depth - 1, false), otherwise);
        }
        if (roll < 41 && depth > 0) {
            Scope inner = scope.child();
            String counter = fresh("i");
            inner.readOnly.add(counter);
            return new Prog.Loop(counter, 1 + random.nextInt(4), block(inner, depth - 1, false));
        }
        if (roll < 48) {
            String[] ops = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>"};
            String op = ops[random.nextInt(ops.length)];
            return new Prog.Compound(pick(scope.locals), op, guarded(op, expression(scope, 1)));
        }
        if (roll < 52) return new Prog.Step(pick(scope.locals), random.nextBoolean());
        if (roll < 52 + pointerShare && !scope.wordLocals().isEmpty()) {
            if (!scope.pointers.isEmpty() && random.nextBoolean()) {
                return new Prog.StoreThrough(pick(scope.pointers), expression(scope, 1));
            }
            String pointer = fresh("q");
            String target = pick(scope.wordLocals());
            scope.pointers.add(pointer);
            return new Prog.PointTo(pointer, target);
        }
        if (roll >= 62 && roll < 72) {
            Prog.Field field = field(scope, false);
            if (field != null) return new Prog.AssignField(field, expression(scope, 2));
        }
        if (roll >= 72 && roll < 77 && !structs.isEmpty()) {
            Prog.StructType type = pick(structs);
            List<Prog.Expr> values = new ArrayList<>();
            for (Prog.Member m : type.members()) {
                if (m.length() == 0) values.add(expression(scope, 1));
            }
            String name = fresh("s");
            scope.structLocals.put(name, type.name());
            return new Prog.DeclareStruct(name, type.name(), values);
        }
        if (roll >= 77 && roll < 80) {
            List<String[]> targets = structTargets(scope);
            if (!targets.isEmpty()) {
                String[] target = pick(targets);
                String pointer = fresh("r");
                scope.structPointers.put(pointer, target[1]);
                return new Prog.PointToStruct(pointer, target[1], target[0]);
            }
        }
        if (roll >= 80 && roll < 84) {
            Prog.Field field = field(scope, true);
            if (field != null) {
                String pointer = fresh("q");
                scope.pointers.add(pointer);
                return new Prog.PointToField(pointer, field);
            }
        }
        if (!scope.pointers.isEmpty() && random.nextInt(4) == 0) {
            return new Prog.StoreThrough(pick(scope.pointers), expression(scope, 1));
        }
        return new Prog.Assign(pick(scope.locals), expression(scope, 2));
    }

    private Prog.Stmt declare(Scope scope) {
        String name = fresh("v");
        Prog.Kind kind = kind();
        Prog.Stmt declare = new Prog.Declare(name, kind, expression(scope, 2));
        scope.locals.add(name);
        scope.kinds.put(name, kind);
        return declare;
    }

    /** Single structs a pointer can take the address of: {name, struct type}. */
    private List<String[]> structTargets(Scope scope) {
        List<String[]> targets = new ArrayList<>();
        scope.structLocals.forEach((name, type) -> targets.add(new String[]{name, type}));
        for (Prog.StructVar v : structGlobals) {
            if (v.count() == 0) targets.add(new String[]{v.name(), v.struct()});
        }
        return targets;
    }

    /**
     * A member of some struct in reach, or null when there is none. For a pointer, only
     * a word scalar will do — {@code &s.m} is a {@code word*}.
     */
    private Prog.Field field(Scope scope, boolean wordScalarOnly) {
        List<Prog.StructRef> refs = new ArrayList<>();
        List<String> types = new ArrayList<>();
        scope.structLocals.forEach((name, type) -> {
            refs.add(new Prog.Named(name));
            types.add(type);
        });
        scope.structPointers.forEach((name, type) -> {
            refs.add(new Prog.Arrow(name));
            types.add(type);
        });
        for (Prog.StructVar v : structGlobals) {
            refs.add(v.count() == 0 ? new Prog.Named(v.name())
                    : new Prog.Element(v.name(), masked(constant(), v.count())));
            types.add(v.struct());
        }
        if (refs.isEmpty()) return null;
        int which = random.nextInt(refs.size());
        Prog.StructType type = structType(types.get(which));
        List<Prog.Member> members = new ArrayList<>();
        for (Prog.Member m : type.members()) {
            if (!wordScalarOnly || (m.kind() == Prog.Kind.WORD && m.length() == 0)) members.add(m);
        }
        if (members.isEmpty()) return null;
        Prog.Member member = pick(members);
        Prog.Expr index = member.length() > 0 ? masked(constant(), member.length()) : null;
        return new Prog.Field(refs.get(which), member.name(), index);
    }

    private Prog.StructType structType(String name) {
        for (Prog.StructType s : structs) if (s.name().equals(name)) return s;
        throw new IllegalArgumentException(name);
    }

    private Prog.Expr expression(Scope scope, int depth) {
        if (depth <= 0 || random.nextInt(4) == 0) return atom(scope);

        // A call, sometimes, and only to something already built.
        if (!scope.callable.isEmpty() && random.nextInt(8) == 0) {
            Prog.Func target = pick(scope.callable);
            List<Prog.Expr> arguments = new ArrayList<>();
            for (int i = 0; i < target.parameters().size(); i++) {
                arguments.add(expression(scope, depth - 1));
            }
            return new Prog.Call(target.name(), arguments);
        }
        switch (random.nextInt(12)) {
            case 0 -> {
                String[] ops = {"-", "~", "!"};
                return new Prog.Unary(ops[random.nextInt(ops.length)], expression(scope, depth - 1));
            }
            case 1 -> {
                return new Prog.Cast(kind(), expression(scope, depth - 1));
            }
            case 2 -> {
                return new Prog.Cond(expression(scope, depth - 1), expression(scope, depth - 1),
                        expression(scope, depth - 1));
            }
            default -> { }
        }

        String[] operators = {"+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>",
                              "==", "!=", "<", "<=", ">", ">=", "&&", "||"};
        String op = operators[random.nextInt(operators.length)];
        Prog.Expr left = expression(scope, depth - 1);
        Prog.Expr right = guarded(op, expression(scope, depth - 1));
        return new Prog.Bin(op, left, right);
    }

    /**
     * The right operand, made safe. Division by zero faults the machine, so a divisor
     * is forced odd. A shift by 16 or more means nothing for a 16-bit value and the
     * simulator's answer there is a JavaScript artefact, so a count is masked.
     */
    private static Prog.Expr guarded(String op, Prog.Expr right) {
        if (op.equals("/") || op.equals("%")) return new Prog.Bin("|", right, new Prog.Const(1));
        if (op.equals("<<") || op.equals(">>")) return new Prog.Bin("&", right, new Prog.Const(15));
        return right;
    }

    private Prog.Expr atom(Scope scope) {
        int choice = random.nextInt(12);
        List<String> readable = scope.readable();
        if (choice < 3 && !readable.isEmpty()) return new Prog.Local(pick(readable));
        if (choice < 5 && !scope.pointers.isEmpty()) return new Prog.Deref(pick(scope.pointers));
        if (choice < 7) {
            Prog.Global g = pick(scope.globals);
            if (g.size() > 0) {
                return new Prog.Index(g.name(), masked(constant(), g.size()));
            }
            return new Prog.GlobalVar(g.name());
        }
        if (choice < 9) {
            Prog.Field field = field(scope, false);
            if (field != null) return new Prog.FieldRead(field);
        }
        if (!readable.isEmpty() && random.nextBoolean()) {
            return new Prog.Local(pick(readable));
        }
        return constant();
    }

    private Prog.Expr constant() {
        // A spread that reaches the interesting boundaries as well as small numbers.
        return switch (random.nextInt(7)) {
            case 0 -> new Prog.Const(0);
            case 1 -> new Prog.Const(1);
            case 2 -> new Prog.Const(65535);
            case 3 -> new Prog.Const(32768);
            case 4 -> new Prog.Const(random.nextInt(256));
            case 5 -> new Prog.Const(128 + random.nextInt(128));   // a byte with its top bit set
            default -> new Prog.Const(random.nextInt(65536));
        };
    }

    /** Keeps an index inside its array; the sizes are powers of two so this is exact. */
    private static Prog.Expr masked(Prog.Expr index, int size) {
        return new Prog.Bin("&", index, new Prog.Const(size - 1));
    }

    private <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }
}
