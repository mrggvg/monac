package dev.madlador.sema;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.parser.ast.Parameter;
import dev.madlador.parser.ast.Program;
import dev.madlador.parser.ast.StructDeclaration;
import dev.madlador.parser.ast.TopLevel;
import dev.madlador.parser.ast.TypeRef;
import dev.madlador.parser.ast.TypeSpecifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every struct in the program, and how each one is laid out.
 *
 * <p>Built in two passes for the same reason the top level is: a member may name a
 * struct declared further down the file, and — more importantly — a struct may name
 * <em>itself</em>, which every linked structure does:
 *
 * <pre>
 *   struct Node {
 *       word value;
 *       struct Node* next;
 *   };
 * </pre>
 *
 * <p>So the first pass creates an empty type per name, and the second fills the
 * members in. A pointer to an unfinished struct is fine — a pointer is two bytes
 * whatever it points at — which is exactly why the recursion works through one and
 * not without.
 *
 * <p>Members whose types are themselves structs have to be laid out first, so the
 * second pass runs to a fixpoint: complete whatever can be completed, repeat, and if
 * a round completes nothing then what is left contains itself by value and is
 * reported as the infinite type it would be.
 *
 * <p><b>Nothing is padded.</b> The machine reads a word from any address, so a member
 * needs no alignment and a struct is exactly the sum of its members. A byte followed
 * by a word is three bytes, with the word starting at an odd offset, and the hardware
 * does not care.
 */
final class StructTable {

    private final DiagnosticReporter reporter;
    private final Names names;
    private final Map<String, Type.Struct> byName = new LinkedHashMap<>();
    private final Map<String, StructDeclaration> declarations = new LinkedHashMap<>();

    /**
     * What only the analyzer knows: what a computed array length comes to, and which
     * enum tags exist. A member may be {@code byte cells[COLUMNS]}, and resolving that
     * needs the constant.
     */
    interface Names {
        /** The value of an array length written as an expression; at least 1. */
        int arrayLength(dev.madlador.parser.ast.Expression length);

        boolean isEnumTag(String tag);
    }

    StructTable(DiagnosticReporter reporter, Names names) {
        this.reporter = reporter;
        this.names = names;
    }

    /** Names every struct, then lays them all out. */
    void build(Program program) {
        for (TopLevel item : program.items()) {
            if (!(item instanceof StructDeclaration declaration)) continue;
            if (byName.containsKey(declaration.name())) {
                // One namespace for both, as in C: `struct U` and `union U` collide.
                reporter.error(declaration.span(),
                        "'" + kind(declaration) + " " + declaration.name() + "' is already declared");
                continue;
            }
            byName.put(declaration.name(),
                    new Type.Struct(declaration.name(), declaration.isUnion()));
            declarations.put(declaration.name(), declaration);
        }
        layOutEverything();
    }

    /**
     * The semantic type for a written one, consulting the table for {@code struct N}.
     *
     * <p>The whole reason this exists rather than {@link Type#of} doing the job: only
     * here is there a table to look a name up in.
     */
    Type resolve(TypeRef ref) {
        Type base;
        if (ref.isStruct()) {
            Type.Struct found = byName.get(ref.structName());
            String written = ref.isUnion() ? "union" : "struct";
            if (found == null) {
                reporter.error(ref.span(), "unknown type '" + written + " " + ref.structName() + "'");
                base = Type.WORD;
            } else {
                if (found.isUnion() != ref.isUnion()) {
                    reporter.error(ref.span(), "'" + ref.structName() + "' is a "
                            + (found.isUnion() ? "union" : "struct") + ", not a " + written);
                }
                base = found;
            }
        } else {
            if (ref.isEnum() && !names.isEnumTag(ref.structName())) {
                reporter.error(ref.span(), "unknown type 'enum " + ref.structName() + "'");
            }
            base = Type.of(ref.base());
        }
        List<Integer> lengths = new ArrayList<>();
        for (TypeRef.Dimension dimension : ref.dimensions()) {
            if (dimension.isComputed()) {
                lengths.add(names.arrayLength(dimension.expression()));
            } else if (dimension.isUnsized()) {
                reporter.error(ref.span(), "an array needs a length here",
                        "only a declaration with an initializer may leave it out");
                lengths.add(1);
            } else {
                lengths.add(dimension.length());
            }
        }
        if (ref.isFunctionPointer()) {
            // A pointer to a function returning what the rest describes, and — outermost
            // — an array of such pointers when the declarator had dimensions.
            List<Type> parameters = new ArrayList<>();
            for (TypeRef parameter : ref.function()) parameters.add(resolve(parameter));
            Type returned = Type.decorate(base, ref.returnPart(), List.of());
            Type type = new Type.Ptr(new Type.Func(returned, parameters));
            for (int i = lengths.size() - 1; i >= 0; i--) type = new Type.Arr(type, lengths.get(i));
            return type;
        }
        return Type.decorate(base, ref, lengths);
    }

    /* ---------------- layout ---------------- */

    private void layOutEverything() {
        List<String> pending = new ArrayList<>(declarations.keySet());

        while (!pending.isEmpty()) {
            List<String> stillPending = new ArrayList<>();
            for (String name : pending) {
                if (readyToLayOut(declarations.get(name))) {
                    layOut(declarations.get(name));
                } else {
                    stillPending.add(name);
                }
            }
            // A round that completed nothing cannot be helped by another one.
            if (stillPending.size() == pending.size()) {
                reportCycle(stillPending);
                return;
            }
            pending = stillPending;
        }
    }

    /** Whether every member whose size this struct needs is already laid out. */
    private boolean readyToLayOut(StructDeclaration declaration) {
        for (Parameter member : declaration.members()) {
            Type.Struct nested = containedStruct(member.type());
            if (nested != null && !nested.isComplete()) return false;
        }
        return true;
    }

    /**
     * The struct a member holds <em>by value</em>, or null.
     *
     * <p>A pointer member does not count, which is the whole point: it is two bytes
     * whether or not the thing it points at has been measured yet.
     */
    private Type.Struct containedStruct(TypeRef ref) {
        if (!ref.isStruct() || ref.pointerDepth() > 0) return null;
        return byName.get(ref.structName());
    }

    private void layOut(StructDeclaration declaration) {
        Type.Struct struct = byName.get(declaration.name());
        List<Type.Field> fields = new ArrayList<>(declaration.members().size());
        int offset = 0;
        // A union's members overlap: each starts at 0, and it is as big as the largest.
        int largest = 0;

        for (Parameter member : declaration.members()) {
            if (fieldNamed(fields, member.name()) != null) {
                reporter.error(member.span(),
                        "duplicate member '" + member.name() + "' in '" + kind(declaration) + " "
                                + declaration.name() + "'");
                continue;
            }
            Type type = resolve(member.type());
            if (type.isVoid()) continue;   // already reported by the parser

            fields.add(new Type.Field(member.name(), type, declaration.isUnion() ? 0 : offset));
            offset += type.size();
            largest = Math.max(largest, type.size());
        }
        int size = declaration.isUnion() ? largest : offset;
        struct.complete(fields, size);

        if (size > MAX_STRUCT_BYTES) {
            reporter.error(declaration.span(),
                    "'" + kind(declaration) + " " + declaration.name() + "' is " + size
                            + " bytes, and at most " + MAX_STRUCT_BYTES + " can be addressed",
                    "a member's offset has to fit the signed-byte displacement of [reg+n]");
        }
    }

    /**
     * The largest struct whose members can all be reached from one base register.
     *
     * <p>{@code [B+n]} encodes {@code n} as a signed byte, so a member beyond 127
     * bytes into a struct could not be named at all. Catching it here, with the
     * declaration to point at, beats a displacement overflow much later.
     */
    private static final int MAX_STRUCT_BYTES = 127;

    private static String kind(StructDeclaration declaration) {
        return declaration.isUnion() ? "union" : "struct";
    }

    private static Type.Field fieldNamed(List<Type.Field> fields, String name) {
        for (Type.Field field : fields) {
            if (field.name().equals(name)) return field;
        }
        return null;
    }

    /**
     * Reports structs that contain themselves by value, and completes them as empty
     * so that everything downstream has a size to work with rather than a null.
     */
    private void reportCycle(List<String> cycle) {
        for (String name : cycle) {
            StructDeclaration declaration = declarations.get(name);
            String kind = kind(declaration);
            reporter.error(declaration.span(),
                    "'" + kind + " " + name + "' contains itself, so it has no size",
                    "hold it by pointer instead: '" + kind + " " + name + "* next;'");
            byName.get(name).complete(List.of(), 0);
        }
    }
}
