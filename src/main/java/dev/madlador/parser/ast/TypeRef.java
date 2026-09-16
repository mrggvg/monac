package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.ArrayList;
import java.util.List;

/**
 * A type as written in the source: a base keyword, some number of {@code *}, and any
 * number of array dimensions.
 *
 * <p>Kept separate from the semantic {@link dev.madlador.sema.Type} so the parser
 * never has to know how types are modelled, and deliberately not a {@link Node},
 * since it has no independent existence in the tree.
 *
 * <p>A struct is written {@code struct Name}, with the keyword required at every
 * use as in C89. That is not ceremony: without it the parser would have to know
 * whether {@code Point} names a type before it can tell {@code Point p;} from an
 * expression statement, which is the lexer feedback loop C is famous for. The
 * keyword keeps this grammar context-free. {@code union Name} and {@code enum Name}
 * work the same way.
 *
 * <p>{@code const} is recorded per level, because C lets it qualify each one on its
 * own: in {@code const byte* const p} both the bytes and the pointer are read-only,
 * and in {@code const byte* p} only the bytes are. Bit 0 of {@code constLevels} is
 * the base type and bit {@code k} the k-th {@code *}. An array's elements are const
 * when bit {@code pointerDepth} is.
 *
 * @param dimensions the array suffixes in source order, outermost first: {@code [2][3]}
 *                   is two rows of three
 * @param decayed    an array parameter, which C reads as a pointer to its first
 *                   element: {@code word v[]} is a {@code word*}, and
 *                   {@code word m[][3]} points to rows of three. The dimension that
 *                   decayed is gone from {@code dimensions}.
 * @param structName the name after {@code struct}, {@code union} or {@code enum}, or
 *                   null for a built-in type
 * @param function   the parameter types, when this is C's function-pointer declarator
 *                   {@code ret (*name)(params)}: a pointer to a function returning what
 *                   the base and pointer levels describe. Its dimensions make an array
 *                   of such pointers, {@code (*handlers[4])(void)}. Null otherwise.
 */
public record TypeRef(TypeSpecifier base, int pointerDepth, List<Dimension> dimensions,
                      boolean decayed, String structName, Span span, int constLevels,
                      List<TypeRef> function) {

    /**
     * One {@code [n]}: a length written as a number, one written as an expression for
     * analysis to evaluate, or none at all — {@code []}, which an initializer counts.
     */
    public record Dimension(int length, Expression expression) {

        private static final int UNSIZED = -1;

        public static Dimension of(int length) {
            return new Dimension(length, null);
        }

        public static Dimension computed(Expression expression) {
            return new Dimension(0, expression);
        }

        public static Dimension unsized() {
            return new Dimension(UNSIZED, null);
        }

        public boolean isComputed() {
            return expression != null;
        }

        public boolean isUnsized() {
            return expression == null && length == UNSIZED;
        }

        String display() {
            return isUnsized() ? "[]" : isComputed() ? "[...]" : "[" + length + "]";
        }
    }

    public TypeRef(TypeSpecifier base, int pointerDepth, String structName, Span span,
                   int constLevels) {
        this(base, pointerDepth, List.of(), false, structName, span, constLevels, null);
    }

    public static TypeRef of(TypeSpecifier base, Span span) {
        return new TypeRef(base, 0, null, span, 0);
    }

    /** A struct or a union: a tagged aggregate the struct table lays out. */
    public boolean isStruct() {
        return base == TypeSpecifier.STRUCT || base == TypeSpecifier.UNION;
    }

    public boolean isUnion() {
        return base == TypeSpecifier.UNION;
    }

    public boolean isEnum() {
        return base == TypeSpecifier.ENUM;
    }

    public boolean isArray() {
        return !dimensions.isEmpty();
    }

    public boolean isPointer() {
        return pointerDepth > 0 || decayed || function != null;
    }

    public boolean isFunctionPointer() {
        return function != null;
    }

    /** True only for a bare {@code void} with no pointer or array decoration. */
    public boolean isPlainVoid() {
        return base == TypeSpecifier.VOID && pointerDepth == 0 && !isArray() && !decayed
                && function == null;
    }

    /** Whether {@code level} is const: 0 is the base type, k the k-th pointer. */
    public boolean isConstAt(int level) {
        return (constLevels & (1 << level)) != 0;
    }

    /**
     * Whether the declared thing itself is read-only, as against what it points at. A
     * decayed array parameter is a fresh pointer, and never const itself.
     */
    public boolean isConst() {
        return !decayed && isConstAt(pointerDepth);
    }

    /** Whether the outermost dimension was left for an initializer to count. */
    public boolean hasUnsizedDimension() {
        return isArray() && dimensions.get(0).isUnsized();
    }

    /** This type with one more dimension, innermost. */
    public TypeRef withDimension(Dimension dimension, Span end) {
        List<Dimension> more = new ArrayList<>(dimensions);
        more.add(dimension);
        return new TypeRef(base, pointerDepth, List.copyOf(more), decayed, structName,
                span.to(end), constLevels, function);
    }

    /** The element type of this array: what one step of its first subscript yields. */
    public TypeRef withoutOuterDimension() {
        return new TypeRef(base, pointerDepth, dimensions.subList(1, dimensions.size()),
                decayed, structName, span, constLevels, function);
    }

    /** This array as a parameter: its outermost dimension becomes a pointer. */
    public TypeRef asParameter() {
        List<Dimension> rest = dimensions.isEmpty() ? List.of()
                : dimensions.subList(1, dimensions.size());
        return new TypeRef(base, pointerDepth, rest, true, structName, span, constLevels,
                function);
    }

    /** A pointer to a function taking {@code parameters} and returning this type. */
    public TypeRef asFunctionPointer(List<TypeRef> parameters, List<Dimension> arrayOf,
                                     Span end) {
        return new TypeRef(base, pointerDepth, List.copyOf(arrayOf), false, structName,
                span.to(end), constLevels, List.copyOf(parameters));
    }

    /** What a function pointer's function returns: this without the declarator. */
    public TypeRef returnPart() {
        return new TypeRef(base, pointerDepth, List.of(), false, structName, span,
                constLevels, null);
    }

    public String display() {
        if (function != null) {
            StringBuilder sb = new StringBuilder(returnPart().display()).append(" (*");
            for (Dimension dimension : dimensions) sb.append(dimension.display());
            sb.append(")(");
            for (int i = 0; i < function.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(function.get(i).display());
            }
            return sb.append(')').toString();
        }
        StringBuilder sb = new StringBuilder();
        if (isConstAt(0)) sb.append("const ");
        sb.append(isStruct() ? base.spelling() + " " + structName
                : isEnum() ? "enum " + structName : base.spelling());
        for (int level = 1; level <= pointerDepth; level++) {
            sb.append('*');
            if (isConstAt(level)) sb.append(" const");
        }
        if (decayed) sb.append(dimensions.isEmpty() ? "*" : "(*)");
        for (Dimension dimension : dimensions) sb.append(dimension.display());
        return sb.toString();
    }
}
