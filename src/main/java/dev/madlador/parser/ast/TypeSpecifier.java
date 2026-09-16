package dev.madlador.parser.ast;

/**
 * A type keyword as written in the source.
 *
 * <p>{@code word}/{@code byte} are unsigned; {@code sword}/{@code sbyte} are two's
 * complement. The distinction matters because the machine has no sign flag and only
 * unsigned conditional branches, so signed comparisons have to be synthesised.
 */
public enum TypeSpecifier {
    VOID(0, false),
    BYTE(1, false),
    WORD(2, false),
    SBYTE(1, true),
    SWORD(2, true),

    /** Size is not known here; the struct table has it. */
    STRUCT(0, false),

    /** A union: laid out by the same table as a struct, every member at offset 0. */
    UNION(0, false),

    /** An enumeration, which is a word whose values have names. */
    ENUM(2, false);

    private final int size;
    private final boolean signed;

    TypeSpecifier(int size, boolean signed) {
        this.size = size;
        this.signed = signed;
    }

    /** Storage width in bytes. */
    public int size() {
        return size;
    }

    public boolean isSigned() {
        return signed;
    }

    public boolean isVoid() {
        return this == VOID;
    }

    /** The source spelling. */
    public String spelling() {
        return name().toLowerCase();
    }
}
