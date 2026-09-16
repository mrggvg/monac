package dev.madlador.sema;

import dev.madlador.parser.ast.TypeSpecifier;

/**
 * A Mona type.
 *
 * <p>Kept deliberately flat. {@code byte}/{@code sbyte} are <em>storage</em> types:
 * everything promotes to a 16-bit value for arithmetic. That is not laziness — the
 * machine's 8-bit registers (AH/AL/BH/BL/...) alias the 16-bit ones, so allowing
 * 8-bit arithmetic would force the register allocator to model sub-register
 * interference across a file of only four registers. Promotion costs nothing when
 * every instruction takes exactly one clock tick.
 */
public sealed interface Type permits Type.Prim, Type.Ptr, Type.Arr, Type.Struct, Type.Func {

    enum Kind { VOID, BYTE, WORD, SBYTE, SWORD }

    record Prim(Kind kind) implements Type {
    }

    /**
     * Pointer to {@code target}. Pointers are unsigned 16-bit addresses.
     *
     * <p>{@code toConst} is C's {@code const T*}: the target may not be written
     * through this pointer. It is deliberately left out of equality. Whether a pointer
     * may write is a question for the places that write, and every other comparison of
     * types — a prototype against its definition, one struct pointer against another
     * — would otherwise have to learn to ignore it.
     */
    record Ptr(Type target, boolean toConst) implements Type {
        public Ptr(Type target) {
            this(target, false);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Ptr p && target.equals(p.target);
        }

        @Override
        public int hashCode() {
            return 31 * target.hashCode() + 1;
        }
    }

    /**
     * A fixed-length array. Decays to {@link Ptr} in most expression contexts.
     *
     * <p>{@code toConst} marks const elements, and carries over to the pointer it
     * decays to; like {@link Ptr}'s, it is not part of equality.
     */
    record Arr(Type element, int length, boolean toConst) implements Type {
        public Arr(Type element, int length) {
            this(element, length, false);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Arr a && length == a.length && element.equals(a.element);
        }

        @Override
        public int hashCode() {
            return 31 * element.hashCode() + length;
        }
    }

    /**
     * A function's signature: what a function pointer points at.
     *
     * <p>Not an object — it has no size and no storage — and it never stands alone for
     * long: a function's name, like {@code *fp}, turns into a pointer to one the moment
     * it is used as a value, as in C. Equality is by signature, which is what makes
     * assigning a pointer of the wrong kind something that can be caught.
     */
    record Func(Type returnType, java.util.List<Type> parameters) implements Type {
    }

    /** One member of a struct, at a byte offset from the struct's first byte. */
    record Field(String name, Type type, int offset) {
    }

    /**
     * A struct.
     *
     * <p>A class rather than a record, and the only mutable thing in this file,
     * because a struct can refer to itself: {@code struct Node { Node* next; }} is
     * the shape every linked structure has. The name is known before the members
     * are, so the type is created empty, registered, and completed once every
     * struct in the program has a name — which is also why a member may name a
     * struct declared further down the file.
     *
     * <p>Identity is by reference and deliberately so. Two structs with the same
     * members are still different types, as in C, and a name declared once yields
     * exactly one instance.
     *
     * <p><b>There is no padding.</b> The machine loads a word from any address —
     * {@code loadWord} reads {@code addr} and {@code addr + 1} with no alignment
     * requirement — so a struct is exactly as large as the sum of its members, and
     * {@code struct { byte flag; word value; }} really is three bytes with the word
     * at an odd offset.
     */
    final class Struct implements Type {
        private final String name;
        private final boolean union;
        private java.util.List<Field> fields;
        private int size;

        public Struct(String name) {
            this(name, false);
        }

        /** A union is a struct whose members all start at offset 0. */
        public Struct(String name, boolean union) {
            this.name = name;
            this.union = union;
        }

        public String name() {
            return name;
        }

        public boolean isUnion() {
            return union;
        }

        /** Whether the members are known yet. Recursion means asking is legitimate. */
        public boolean isComplete() {
            return fields != null;
        }

        public java.util.List<Field> fields() {
            return fields == null ? java.util.List.of() : fields;
        }

        /** Called once, after every struct in the program has been named. */
        public void complete(java.util.List<Field> members, int totalSize) {
            this.fields = java.util.List.copyOf(members);
            this.size = totalSize;
        }

        public Field field(String memberName) {
            for (Field field : fields()) {
                if (field.name().equals(memberName)) return field;
            }
            return null;
        }

        int byteSize() {
            return size;
        }

        @Override
        public String toString() {
            return (union ? "union " : "struct ") + name;
        }
    }

    Type VOID = new Prim(Kind.VOID);
    Type BYTE = new Prim(Kind.BYTE);
    Type WORD = new Prim(Kind.WORD);
    Type SBYTE = new Prim(Kind.SBYTE);
    Type SWORD = new Prim(Kind.SWORD);

    /** The semantic type for a syntactic one whose array lengths are all numbers. */
    static Type of(dev.madlador.parser.ast.TypeRef ref) {
        if (ref.isFunctionPointer()) {
            throw new IllegalArgumentException("a function pointer type needs the struct table");
        }
        java.util.List<Integer> lengths = new java.util.ArrayList<>();
        for (dev.madlador.parser.ast.TypeRef.Dimension dimension : ref.dimensions()) {
            if (dimension.isComputed() || dimension.isUnsized()) {
                throw new IllegalArgumentException("an array length needs analysis");
            }
            lengths.add(dimension.length());
        }
        return decorate(of(ref.base()), ref, lengths);
    }

    /**
     * The pointer levels and array suffix of {@code ref}, applied to a base that has
     * already been resolved, with each level's {@code const} carried onto the pointer
     * that points at it. {@code lengths} are the array's, outermost first, worked out
     * by the caller where the source wrote an expression rather than a number.
     *
     * <p>Dimensions nest from the inside out, so {@code [2][3]} is two arrays of three.
     * A decayed parameter wraps the result in one more pointer — to the first element,
     * which for {@code word m[][3]} is a row.
     */
    static Type decorate(Type base, dev.madlador.parser.ast.TypeRef ref,
                         java.util.List<Integer> lengths) {
        Type type = base;
        for (int level = 1; level <= ref.pointerDepth(); level++) {
            type = new Ptr(type, ref.isConstAt(level - 1));
        }
        boolean constElements = ref.isConstAt(ref.pointerDepth());
        for (int i = lengths.size() - 1; i >= 0; i--) {
            type = new Arr(type, lengths.get(i), constElements);
        }
        if (ref.decayed()) type = new Ptr(type, constElements);
        return type;
    }

    /**
     * The built-in type a keyword names.
     *
     * <p>{@code struct} is not one of them: only {@code StructTable} knows what a
     * struct name means, so resolving one goes through there instead.
     */
    static Type of(TypeSpecifier specifier) {
        return switch (specifier) {
            case VOID -> VOID;
            case BYTE -> BYTE;
            case WORD -> WORD;
            case SBYTE -> SBYTE;
            case SWORD -> SWORD;
            case ENUM -> WORD;
            case STRUCT, UNION -> throw new IllegalArgumentException(
                    "a struct type must be resolved through the struct table");
        };
    }

    /** Storage size in bytes. */
    default int size() {
        return switch (this) {
            case Prim p -> switch (p.kind()) {
                case VOID -> 0;
                case BYTE, SBYTE -> 1;
                case WORD, SWORD -> 2;
            };
            case Ptr ignored -> 2;
            case Arr a -> a.element().size() * a.length();
            case Struct s -> s.byteSize();
            case Func ignored -> 0;
        };
    }

    /**
     * Size of a local variable's frame slot. Scalars always occupy a full word:
     * packing a byte local would save nothing against 4&nbsp;KB of RAM and would
     * complicate the already-odd frame offsets.
     */
    default int slotSize() {
        return switch (this) {
            case Arr a -> Math.max(2, a.element().size() * a.length());
            case Struct s -> Math.max(2, s.byteSize());
            default -> 2;
        };
    }

    default boolean isSigned() {
        return this instanceof Prim p && (p.kind() == Kind.SBYTE || p.kind() == Kind.SWORD);
    }

    default boolean isVoid() {
        return this instanceof Prim p && p.kind() == Kind.VOID;
    }

    /** True for the one-byte storage types, which need MOVB rather than MOV. */
    default boolean isByteWidth() {
        return this instanceof Prim p && (p.kind() == Kind.BYTE || p.kind() == Kind.SBYTE);
    }

    default boolean isInteger() {
        return this instanceof Prim p && p.kind() != Kind.VOID;
    }

    default boolean isPointer() {
        return this instanceof Ptr || this instanceof Arr;
    }

    default boolean isStruct() {
        return this instanceof Struct;
    }

    /** This type as a struct, or null. For {@code p->x}, ask the pointee. */
    default Struct asStruct() {
        return this instanceof Struct s ? s : null;
    }

    /** The type an operand has after the usual promotion to 16 bits. */
    default Type promoted() {
        return switch (this) {
            case Prim p -> switch (p.kind()) {
                case BYTE -> WORD;
                case SBYTE -> SWORD;
                default -> this;
            };
            case Arr a -> new Ptr(a.element(), a.toConst());
            case Func f -> new Ptr(f);
            default -> this;
        };
    }

    /** What a pointer or array yields when dereferenced, or null if it is neither. */
    default Type pointee() {
        return switch (this) {
            case Ptr p -> p.target();
            case Arr a -> a.element();
            default -> null;
        };
    }

    /** The signature this can be called with — a function, or a pointer to one — or null. */
    default Func callable() {
        if (this instanceof Func f) return f;
        if (this instanceof Ptr p && p.target() instanceof Func f) return f;
        return null;
    }

    /** Whether this is a pointer, or an array, whose target may not be written. */
    default boolean pointsToConst() {
        return (this instanceof Ptr p && p.toConst()) || (this instanceof Arr a && a.toConst());
    }

    default String display() {
        return switch (this) {
            case Prim p -> p.kind().name().toLowerCase();
            case Ptr p when p.target() instanceof Func f ->
                    f.returnType().display() + " (*)" + parameterDisplay(f);
            case Ptr p -> (p.toConst() ? constDisplay(p.target()) : p.target().display()) + "*";
            case Arr a -> (a.toConst() ? constDisplay(a.element()) : a.element().display())
                    + "[" + a.length() + "]";
            case Struct s -> s.toString();
            case Func f -> f.returnType().display() + parameterDisplay(f);
        };
    }

    private static String parameterDisplay(Func function) {
        if (function.parameters().isEmpty()) return "(void)";   // as C writes it
        StringBuilder text = new StringBuilder("(");
        for (int i = 0; i < function.parameters().size(); i++) {
            if (i > 0) text.append(", ");
            text.append(function.parameters().get(i).display());
        }
        return text.append(')').toString();
    }

    /** How C writes a const type: in front of a scalar, after a pointer. */
    private static String constDisplay(Type type) {
        return type instanceof Ptr ? type.display() + " const" : "const " + type.display();
    }
}
