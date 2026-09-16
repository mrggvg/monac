package dev.madlador.fuzz;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A generated program: one tree, printed as Mona and interpreted as the answer.
 *
 * <p>The printer and the interpreter live in the same file on purpose. They are the two
 * halves of a differential test, and the moment they disagree about what a node means
 * the whole exercise reports failures that are not there — so they are written next to
 * each other, node for node, where a change to one makes the other's absence obvious.
 *
 * <p>Everything is {@code word}: unsigned, sixteen bits, wrapping. That is not a
 * simplification of the machine, it is the machine.
 */
public final class Prog {

    private Prog() {
    }

    /** The four scalar types, as storage: two widths, two signednesses. */
    public enum Kind {
        WORD, BYTE, SBYTE, SWORD;

        public boolean signed() {
            return this == SBYTE || this == SWORD;
        }

        /**
         * A 16-bit value as a variable of this type holds it — and so as it reads back:
         * a byte keeps its low eight bits, an sbyte sign-extends them.
         */
        public int store(int value) {
            return switch (this) {
                case BYTE -> value & 0xFF;
                case SBYTE -> ((byte) value) & 0xFFFF;
                default -> value & 0xFFFF;
            };
        }

        public String spelling() {
            return name().toLowerCase();
        }
    }

    /* ---------------- the tree ---------------- */

    public sealed interface Expr
            permits Const, Local, GlobalVar, Index, Bin, Call, Deref, Unary, Cast, Cond, FieldRead {
    }

    public record Const(int value) implements Expr {
    }

    /** A parameter or a local. */
    public record Local(String name) implements Expr {
    }

    public record GlobalVar(String name) implements Expr {
    }

    /** {@code g[e]} — the index is masked to the array's size by the generator. */
    public record Index(String array, Expr index) implements Expr {
    }

    public record Bin(String op, Expr left, Expr right) implements Expr {
    }

    /** {@code -e}, {@code ~e} or {@code !e}. */
    public record Unary(String op, Expr operand) implements Expr {
    }

    /** {@code (kind)e}. */
    public record Cast(Kind kind, Expr operand) implements Expr {
    }

    /** {@code c ? a : b} — only the arm chosen is evaluated. */
    public record Cond(Expr condition, Expr then, Expr otherwise) implements Expr {
    }

    public record Call(String callee, List<Expr> arguments) implements Expr {
    }

    /** {@code *p}, where {@code p} was set to the address of a word: a local or a member. */
    public record Deref(String pointer) implements Expr {
    }

    public record FieldRead(Field field) implements Expr {
    }

    /** Which struct: a named one, an element of a global array of them, or through a pointer. */
    public sealed interface StructRef permits Named, Element, Arrow {
    }

    /** A struct local, or a global that is a single struct. */
    public record Named(String variable) implements StructRef {
    }

    /** {@code a[i]} of a global array of structs; the index is masked. */
    public record Element(String array, Expr index) implements StructRef {
    }

    /** {@code p->}, where {@code p} points at a struct. */
    public record Arrow(String pointer) implements StructRef {
    }

    /** {@code s.m}, or {@code s.m[i]} for an array member, whose index is masked. */
    public record Field(StructRef struct, String member, Expr index) {
    }

    public sealed interface Stmt
            permits Declare, Assign, AssignGlobal, AssignIndex, PointTo, StoreThrough,
                    If, Loop, Return, AssignField, DeclareStruct, PointToStruct,
                    PointToField, Compound, Step {
    }

    public record Declare(String name, Kind kind, Expr value) implements Stmt {
    }

    public record Assign(String name, Expr value) implements Stmt {
    }

    public record AssignGlobal(String name, Expr value) implements Stmt {
    }

    public record AssignIndex(String array, Expr index, Expr value) implements Stmt {
    }

    /** {@code word* p = &x;} — declaring a pointer at the address of a word local. */
    public record PointTo(String pointer, String target) implements Stmt {
    }

    /** {@code *p = e;} */
    public record StoreThrough(String pointer, Expr value) implements Stmt {
    }

    public record If(Expr condition, List<Stmt> then, List<Stmt> otherwise) implements Stmt {
    }

    /**
     * A counted loop, and only a counted loop.
     *
     * <p>The counter is declared, tested and incremented by the printer rather than by
     * the generator, so no generated statement can touch it. A fuzzer that can emit a
     * loop it cannot prove terminates spends its time timing out instead of finding
     * bugs.
     */
    public record Loop(String counter, int times, List<Stmt> body) implements Stmt {
    }

    public record Return(Expr value) implements Stmt {
    }

    /** {@code s.m = e;} */
    public record AssignField(Field field, Expr value) implements Stmt {
    }

    /**
     * {@code struct S v = {...};} — one value per scalar member, in order; an array
     * member gets {@code {}} and so starts at zero.
     */
    public record DeclareStruct(String name, String struct, List<Expr> values) implements Stmt {
    }

    /** {@code struct S* p = &v;} */
    public record PointToStruct(String pointer, String struct, String target) implements Stmt {
    }

    /** {@code word* q = &s.m;} — the member is a word; this is where {@code &member} broke. */
    public record PointToField(String pointer, Field field) implements Stmt {
    }

    /** {@code x op= e;} on a local. */
    public record Compound(String name, String op, Expr value) implements Stmt {
    }

    /** {@code x++;} or {@code x--;} on a local. */
    public record Step(String name, boolean increment) implements Stmt {
    }

    /** A global: a scalar when {@code size} is 0, an array otherwise. */
    public record Global(String name, Kind kind, int size) {
    }

    /** A struct member: a scalar when {@code length} is 0, an array otherwise. */
    public record Member(String name, Kind kind, int length) {
    }

    public record StructType(String name, List<Member> members) {
        public Member member(String name) {
            for (Member m : members) if (m.name().equals(name)) return m;
            throw new IllegalArgumentException("no member " + name);
        }
    }

    /** A global struct: one when {@code count} is 0, an array of them otherwise. */
    public record StructVar(String name, String struct, int count) {
    }

    public record Func(String name, List<String> parameters, List<Stmt> body) {
    }

    public record Program(List<StructType> structs, List<Global> globals,
                          List<StructVar> structGlobals, List<Func> functions) {
        public Program withFunctions(List<Func> replaced) {
            return new Program(structs, globals, structGlobals, replaced);
        }

        public StructType struct(String name) {
            for (StructType s : structs) if (s.name().equals(name)) return s;
            throw new IllegalArgumentException("no struct " + name);
        }
    }

    /* ---------------- printing ---------------- */

    public static String print(Program program) {
        StringBuilder out = new StringBuilder();
        for (StructType s : program.structs()) {
            out.append("struct ").append(s.name()).append(" {");
            for (Member m : s.members()) {
                out.append(' ').append(m.kind().spelling()).append(' ').append(m.name());
                if (m.length() > 0) out.append('[').append(m.length()).append(']');
                out.append(';');
            }
            out.append(" };\n");
        }
        for (Global g : program.globals()) {
            out.append(g.kind().spelling()).append(' ').append(g.name());
            if (g.size() > 0) out.append('[').append(g.size()).append(']');
            out.append(";\n");
        }
        for (StructVar v : program.structGlobals()) {
            out.append("struct ").append(v.struct()).append(' ').append(v.name());
            if (v.count() > 0) out.append('[').append(v.count()).append(']');
            out.append(";\n");
        }
        out.append('\n');
        for (Func f : program.functions()) {
            out.append("word ").append(f.name()).append('(');
            for (int i = 0; i < f.parameters().size(); i++) {
                if (i > 0) out.append(", ");
                out.append("word ").append(f.parameters().get(i));
            }
            out.append(") {\n");
            printBody(out, program, f.body(), 1);
            out.append("}\n\n");
        }
        return out.toString();
    }

    private static void printBody(StringBuilder out, Program program, List<Stmt> body, int depth) {
        for (Stmt s : body) print(out, program, s, depth);
    }

    private static void indent(StringBuilder out, int depth) {
        out.append("    ".repeat(depth));
    }

    private static void print(StringBuilder out, Program program, Stmt statement, int depth) {
        indent(out, depth);
        switch (statement) {
            case Declare d -> out.append(d.kind().spelling()).append(' ').append(d.name())
                    .append(" = ").append(print(d.value())).append(";\n");
            case Assign a -> out.append(a.name()).append(" = ")
                    .append(print(a.value())).append(";\n");
            case AssignGlobal a -> out.append(a.name()).append(" = ")
                    .append(print(a.value())).append(";\n");
            case AssignIndex a -> out.append(a.array()).append('[').append(print(a.index()))
                    .append("] = ").append(print(a.value())).append(";\n");
            case PointTo p -> out.append("word* ").append(p.pointer()).append(" = &")
                    .append(p.target()).append(";\n");
            case StoreThrough s -> out.append('*').append(s.pointer()).append(" = ")
                    .append(print(s.value())).append(";\n");
            case Return r -> out.append("return ").append(print(r.value())).append(";\n");
            case AssignField a -> out.append(print(a.field())).append(" = ")
                    .append(print(a.value())).append(";\n");
            case DeclareStruct d -> {
                out.append("struct ").append(d.struct()).append(' ').append(d.name()).append(" = {");
                int next = 0;
                List<Member> members = program.struct(d.struct()).members();
                for (int i = 0; i < members.size(); i++) {
                    if (i > 0) out.append(", ");
                    out.append(members.get(i).length() > 0 ? "{}" : print(d.values().get(next++)));
                }
                out.append("};\n");
            }
            case PointToStruct p -> out.append("struct ").append(p.struct()).append("* ")
                    .append(p.pointer()).append(" = &").append(p.target()).append(";\n");
            case PointToField p -> out.append("word* ").append(p.pointer()).append(" = &")
                    .append(print(p.field())).append(";\n");
            case Compound c -> out.append(c.name()).append(' ').append(c.op()).append("= ")
                    .append(print(c.value())).append(";\n");
            case Step s -> out.append(s.name()).append(s.increment() ? "++" : "--").append(";\n");
            case If i -> {
                out.append("if (").append(print(i.condition())).append(") {\n");
                printBody(out, program, i.then(), depth + 1);
                indent(out, depth);
                if (i.otherwise().isEmpty()) {
                    out.append("}\n");
                } else {
                    out.append("} else {\n");
                    printBody(out, program, i.otherwise(), depth + 1);
                    indent(out, depth);
                    out.append("}\n");
                }
            }
            case Loop l -> {
                out.append("for (word ").append(l.counter()).append(" = 0; ")
                        .append(l.counter()).append(" < ").append(l.times()).append("; ")
                        .append(l.counter()).append(" += 1) {\n");
                printBody(out, program, l.body(), depth + 1);
                indent(out, depth);
                out.append("}\n");
            }
        }
    }

    public static String print(Field field) {
        String base = switch (field.struct()) {
            case Named n -> n.variable() + ".";
            case Element e -> e.array() + "[" + print(e.index()) + "].";
            case Arrow a -> a.pointer() + "->";
        };
        String member = base + field.member();
        return field.index() == null ? member : member + "[" + print(field.index()) + "]";
    }

    public static String print(Expr expression) {
        return switch (expression) {
            case Const c -> Integer.toString(c.value());
            case Local l -> l.name();
            case GlobalVar g -> g.name();
            case Index i -> i.array() + "[" + print(i.index()) + "]";
            case Deref d -> "*" + d.pointer();
            case Bin b -> "(" + print(b.left()) + " " + b.op() + " " + print(b.right()) + ")";
            case Unary u -> "(" + u.op() + print(u.operand()) + ")";
            case Cast c -> "((" + c.kind().spelling() + ")" + print(c.operand()) + ")";
            case Cond c -> "(" + print(c.condition()) + " ? " + print(c.then()) + " : "
                    + print(c.otherwise()) + ")";
            case FieldRead f -> print(f.field());
            case Call c -> {
                StringBuilder sb = new StringBuilder(c.callee()).append('(');
                for (int i = 0; i < c.arguments().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(print(c.arguments().get(i)));
                }
                yield sb.append(')').toString();
            }
        };
    }
}
