package dev.madlador.fuzz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The other half of the differential test: what the program should produce.
 *
 * <p>Written in terms of what {@code word} means on this machine — unsigned, sixteen
 * bits, wrapping — rather than in terms of Java's {@code int}. Every arithmetic result
 * is masked, division is unsigned, and a shift is logical. Getting any of those subtly
 * wrong would make the fuzzer report the compiler as broken when it is not, which is
 * worse than not having a fuzzer.
 *
 * <p>Recursion is impossible by construction — the generator only lets a function call
 * ones defined after it — so the depth needs no guard.
 */
public final class Interpreter {

    /** Thrown when a generated program does something the machine could not. */
    public static final class Trap extends RuntimeException {
        Trap(String message) {
            super(message);
        }
    }

    private final Prog.Program program;
    private final Map<String, Prog.Func> functions = new HashMap<>();
    private final Map<String, Prog.Global> globalDeclarations = new HashMap<>();
    private final Map<String, int[]> globals = new HashMap<>();
    private final Map<String, Instance[]> structGlobals = new HashMap<>();

    /** Enough to notice a runaway before a test does. */
    private static final int STEP_LIMIT = 2_000_000;
    private int steps;

    /** One struct: each member's values, an array member's several. */
    private static final class Instance {
        final Prog.StructType type;
        final Map<String, int[]> members = new HashMap<>();

        Instance(Prog.StructType type) {
            this.type = type;
            for (Prog.Member m : type.members()) members.put(m.name(), new int[Math.max(1, m.length())]);
        }
    }

    /** Somewhere a word can be read and written: a local, or one member slot. */
    private interface Ref {
        int get();

        void set(int value);
    }

    public Interpreter(Prog.Program program) {
        this.program = program;
        for (Prog.Func f : program.functions()) functions.put(f.name(), f);
        for (Prog.Global g : program.globals()) {
            globalDeclarations.put(g.name(), g);
            globals.put(g.name(), new int[Math.max(1, g.size())]);
        }
        for (Prog.StructVar v : program.structGlobals()) {
            Instance[] instances = new Instance[Math.max(1, v.count())];
            for (int i = 0; i < instances.length; i++) instances[i] = new Instance(program.struct(v.struct()));
            structGlobals.put(v.name(), instances);
        }
    }

    /** Runs {@code main} and returns what the machine would leave in A. */
    public int run() {
        return call("main", List.of());
    }

    private int call(String name, List<Integer> arguments) {
        Prog.Func function = functions.get(name);
        if (function == null) throw new Trap("no function " + name);

        Frame frame = new Frame();
        for (int i = 0; i < function.parameters().size(); i++) {
            frame.locals.put(function.parameters().get(i), arguments.get(i));
        }
        Integer returned = execute(function.body(), frame);
        // Mona returns 0 from a function that runs off the end.
        return returned == null ? 0 : returned;
    }

    /** A function's locals, their types, and where its pointers point. */
    private static final class Frame {
        final Map<String, Integer> locals = new HashMap<>();
        /** A local's declared type; parameters and loop counters are words. */
        final Map<String, Prog.Kind> kinds = new HashMap<>();
        final Map<String, Ref> pointers = new HashMap<>();
        final Map<String, Instance> structs = new HashMap<>();
        final Map<String, Instance> structPointers = new HashMap<>();

        Prog.Kind kind(String local) {
            return kinds.getOrDefault(local, Prog.Kind.WORD);
        }
    }

    /** Returns the value returned, or null if the block finished without returning. */
    private Integer execute(List<Prog.Stmt> body, Frame frame) {
        for (Prog.Stmt statement : body) {
            if (++steps > STEP_LIMIT) throw new Trap("the reference ran away");
            switch (statement) {
                case Prog.Declare d -> {
                    frame.kinds.put(d.name(), d.kind());
                    frame.locals.put(d.name(), d.kind().store(eval(d.value(), frame)));
                }
                case Prog.Assign a -> frame.locals.put(a.name(),
                        frame.kind(a.name()).store(eval(a.value(), frame)));
                case Prog.AssignGlobal a -> globals.get(a.name())[0] =
                        globalDeclarations.get(a.name()).kind().store(eval(a.value(), frame));
                case Prog.AssignIndex a -> {
                    // The value before the index, because that is the order the
                    // compiler evaluates an assignment in; both may call something.
                    int value = eval(a.value(), frame);
                    int[] array = globals.get(a.array());
                    int index = eval(a.index(), frame) % array.length;
                    array[index] = globalDeclarations.get(a.array()).kind().store(value);
                }
                case Prog.PointTo p -> frame.pointers.put(p.pointer(), localRef(frame, p.target()));
                case Prog.StoreThrough s -> {
                    Ref target = frame.pointers.get(s.pointer());
                    if (target == null) throw new Trap("pointer " + s.pointer() + " unset");
                    target.set(eval(s.value(), frame) & 0xFFFF);
                }
                case Prog.Return r -> {
                    return eval(r.value(), frame);
                }
                case Prog.If i -> {
                    List<Prog.Stmt> branch = eval(i.condition(), frame) != 0
                            ? i.then() : i.otherwise();
                    Integer returned = execute(branch, frame);
                    if (returned != null) return returned;
                }
                case Prog.Loop l -> {
                    for (int i = 0; i < l.times(); i++) {
                        frame.locals.put(l.counter(), i);
                        Integer returned = execute(l.body(), frame);
                        if (returned != null) return returned;
                    }
                    // Scoped to the loop in Mona, so nothing can observe it afterwards.
                    frame.locals.remove(l.counter());
                }
                case Prog.AssignField a -> {
                    int value = eval(a.value(), frame);
                    Slot slot = slot(a.field(), frame);
                    slot.values()[slot.index()] = slot.kind().store(value);
                }
                case Prog.DeclareStruct d -> {
                    Instance instance = new Instance(program.struct(d.struct()));
                    int next = 0;
                    for (Prog.Member m : instance.type.members()) {
                        if (m.length() > 0) continue;   // `{}`: zero
                        instance.members.get(m.name())[0] = m.kind().store(eval(d.values().get(next++), frame));
                    }
                    frame.structs.put(d.name(), instance);
                }
                case Prog.PointToStruct p -> frame.structPointers.put(p.pointer(), named(p.target(), frame));
                case Prog.PointToField p -> {
                    Slot slot = slot(p.field(), frame);
                    frame.pointers.put(p.pointer(), new Ref() {
                        public int get() {
                            return slot.values()[slot.index()];
                        }

                        public void set(int value) {
                            slot.values()[slot.index()] = value;
                        }
                    });
                }
                case Prog.Compound c -> {
                    // `x op= e` is `x = x op e`: x is read first, then e.
                    Prog.Kind kind = frame.kind(c.name());
                    int old = local(frame, c.name());
                    int value = eval(c.value(), frame);
                    boolean signed = signedFor(c.op(), kind.signed(), signedOf(c.value(), frame));
                    frame.locals.put(c.name(), kind.store(apply(c.op(), old, value, signed)));
                }
                case Prog.Step s -> {
                    Prog.Kind kind = frame.kind(s.name());
                    frame.locals.put(s.name(), kind.store(local(frame, s.name()) + (s.increment() ? 1 : -1)));
                }
            }
        }
        return null;
    }

    private static int local(Frame frame, String name) {
        Integer value = frame.locals.get(name);
        if (value == null) throw new Trap("local " + name + " unset");
        return value;
    }

    private static Ref localRef(Frame frame, String name) {
        return new Ref() {
            public int get() {
                return local(frame, name);
            }

            public void set(int value) {
                frame.locals.put(name, value);
            }
        };
    }

    /** One member slot: where it lives, which element, and its type. */
    private record Slot(int[] values, int index, Prog.Kind kind) {
    }

    private Instance named(String name, Frame frame) {
        Instance local = frame.structs.get(name);
        if (local != null) return local;
        Instance[] global = structGlobals.get(name);
        if (global == null) throw new Trap("no struct " + name);
        return global[0];
    }

    private Instance instance(Prog.StructRef ref, Frame frame) {
        return switch (ref) {
            case Prog.Named n -> named(n.variable(), frame);
            case Prog.Element e -> {
                Instance[] array = structGlobals.get(e.array());
                yield array[eval(e.index(), frame) % array.length];
            }
            case Prog.Arrow a -> {
                Instance target = frame.structPointers.get(a.pointer());
                if (target == null) throw new Trap("pointer " + a.pointer() + " unset");
                yield target;
            }
        };
    }

    private Slot slot(Prog.Field field, Frame frame) {
        Instance instance = instance(field.struct(), frame);
        Prog.Member member = instance.type.member(field.member());
        int[] values = instance.members.get(field.member());
        int index = field.index() == null ? 0 : eval(field.index(), frame) % values.length;
        return new Slot(values, index, member.kind());
    }

    /** The struct type a reference names, without evaluating anything. */
    private Prog.StructType typeOf(Prog.StructRef ref, Frame frame) {
        return switch (ref) {
            case Prog.Named n -> named(n.variable(), frame).type;
            case Prog.Element e -> structGlobals.get(e.array())[0].type;
            case Prog.Arrow a -> {
                Instance target = frame.structPointers.get(a.pointer());
                if (target == null) throw new Trap("pointer " + a.pointer() + " unset");
                yield target.type;
            }
        };
    }

    /**
     * Whether an expression is signed, by the analyzer's rules and without evaluating
     * it: bytes widen, an operator is signed if either operand is, a shift takes its
     * left operand's type, a comparison is a word.
     */
    private boolean signedOf(Prog.Expr expression, Frame frame) {
        return switch (expression) {
            case Prog.Const ignored -> false;
            case Prog.Local l -> frame.kind(l.name()).signed();
            case Prog.GlobalVar g -> globalDeclarations.get(g.name()).kind().signed();
            case Prog.Index i -> globalDeclarations.get(i.array()).kind().signed();
            case Prog.Deref ignored -> false;
            case Prog.Call ignored -> false;
            case Prog.FieldRead f -> typeOf(f.field().struct(), frame).member(f.field().member()).kind().signed();
            case Prog.Unary u -> !u.op().equals("!") && signedOf(u.operand(), frame);
            case Prog.Cast c -> c.kind().signed();
            case Prog.Cond c -> signedOf(c.then(), frame) || signedOf(c.otherwise(), frame);
            case Prog.Bin b -> switch (b.op()) {
                case "==", "!=", "<", "<=", ">", ">=", "&&", "||" -> false;
                case "<<", ">>" -> signedOf(b.left(), frame);
                default -> signedOf(b.left(), frame) || signedOf(b.right(), frame);
            };
        };
    }

    /** Whether {@code op} works signed, given its operands' signedness. */
    private static boolean signedFor(String op, boolean left, boolean right) {
        return op.equals("<<") || op.equals(">>") ? left : left || right;
    }

    private int eval(Prog.Expr expression, Frame frame) {
        if (++steps > STEP_LIMIT) throw new Trap("the reference ran away");
        return switch (expression) {
            case Prog.Const c -> c.value() & 0xFFFF;
            case Prog.Local l -> local(frame, l.name());
            case Prog.GlobalVar g -> globals.get(g.name())[0];
            case Prog.Index i -> {
                int[] array = globals.get(i.array());
                yield array[eval(i.index(), frame) % array.length];
            }
            case Prog.Deref d -> {
                Ref target = frame.pointers.get(d.pointer());
                if (target == null) throw new Trap("pointer " + d.pointer() + " unset");
                yield target.get();
            }
            case Prog.FieldRead f -> {
                Slot slot = slot(f.field(), frame);
                yield slot.values()[slot.index()];
            }
            case Prog.Unary u -> {
                int value = eval(u.operand(), frame);
                yield switch (u.op()) {
                    case "-" -> (-value) & 0xFFFF;
                    case "~" -> (~value) & 0xFFFF;
                    case "!" -> value == 0 ? 1 : 0;
                    default -> throw new IllegalArgumentException(u.op());
                };
            }
            case Prog.Cast c -> c.kind().store(eval(c.operand(), frame));
            case Prog.Cond c -> eval(c.condition(), frame) != 0
                    ? eval(c.then(), frame) : eval(c.otherwise(), frame);
            case Prog.Bin b -> {
                // && and || SHORT-CIRCUIT in Mona, so the right side must not be
                // evaluated when the left decides the answer. Evaluating both here
                // would be a reference that disagrees with the language the moment a
                // right operand calls something that writes a global -- a failure
                // reported against the compiler that was the fuzzer's own fault.
                if (b.op().equals("&&")) {
                    yield eval(b.left(), frame) != 0 && eval(b.right(), frame) != 0 ? 1 : 0;
                }
                if (b.op().equals("||")) {
                    yield eval(b.left(), frame) != 0 || eval(b.right(), frame) != 0 ? 1 : 0;
                }
                boolean signed = signedFor(b.op(), signedOf(b.left(), frame), signedOf(b.right(), frame));
                int left = eval(b.left(), frame);
                int right = eval(b.right(), frame);
                yield apply(b.op(), left, right, signed);
            }
            case Prog.Call c -> {
                List<Integer> arguments = new ArrayList<>(c.arguments().size());
                for (Prog.Expr argument : c.arguments()) arguments.add(eval(argument, frame));
                yield call(c.callee(), arguments);
            }
        };
    }

    /**
     * One operator, in the machine's terms.
     *
     * <p>The generator guarantees a non-zero divisor and a shift count below 16, so
     * neither needs defending here. What does need care is signedness: unsigned
     * division and a logical shift are what Java's operators are not, and signed ones
     * must truncate toward zero and keep the sign, as the runtime helpers do.
     */
    static int apply(String op, int a, int b, boolean signed) {
        short sa = (short) a;
        short sb = (short) b;
        int result = switch (op) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "*" -> a * b;
            case "/" -> signed ? sa / sb : Integer.divideUnsigned(a, b);
            case "%" -> signed ? sa % sb : Integer.remainderUnsigned(a, b);
            case "&" -> a & b;
            case "|" -> a | b;
            case "^" -> a ^ b;
            case "<<" -> a << b;
            case ">>" -> signed ? sa >> b : a >>> b;
            case "==" -> a == b ? 1 : 0;
            case "!=" -> a != b ? 1 : 0;
            case "<" -> (signed ? Short.compare(sa, sb) : Integer.compareUnsigned(a, b)) < 0 ? 1 : 0;
            case "<=" -> (signed ? Short.compare(sa, sb) : Integer.compareUnsigned(a, b)) <= 0 ? 1 : 0;
            case ">" -> (signed ? Short.compare(sa, sb) : Integer.compareUnsigned(a, b)) > 0 ? 1 : 0;
            case ">=" -> (signed ? Short.compare(sa, sb) : Integer.compareUnsigned(a, b)) >= 0 ? 1 : 0;
            // && and || never reach here: they short-circuit in eval.
            default -> throw new IllegalArgumentException(op);
        };
        return result & 0xFFFF;
    }
}
