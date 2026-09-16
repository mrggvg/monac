package dev.madlador.sema;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * A named entity: a local, a parameter, a global, or a function.
 *
 * <p>Every symbol that reaches the assembler carries a <em>mangled</em> label. That
 * is not decoration: the target assembler uppercases labels (so {@code foo} and
 * {@code Foo} collide), rejects labels that spell a register name (so a Mona
 * function called {@code a} would fail to assemble), and rejects labels beginning
 * with an underscore outright.
 */
public sealed interface Symbol
        permits Symbol.LocalVar, Symbol.ParamVar, Symbol.GlobalVar, Symbol.Func,
                Symbol.Constant {

    String name();

    Span span();

    /** A local variable, identified by its frame slot index. */
    record LocalVar(String name, Type type, int slot, Span span) implements Symbol {
    }

    /**
     * A parameter, identified by its position in the argument list.
     *
     * <p>Argument 0 arrives in a register rather than on the stack, so it has no
     * position on the stack to be read from and is given a frame slot of its own, the
     * same as a local. {@code slot} is that slot, or {@link #NO_SLOT} for the
     * arguments that really were pushed.
     */
    record ParamVar(String name, Type type, int index, int slot, Span span) implements Symbol {

        public static final int NO_SLOT = -1;

        /** Whether this parameter arrived in a register. */
        public boolean inRegister() {
            return slot != NO_SLOT;
        }
    }

    /** A global variable, addressed by label. */
    record GlobalVar(String name, Type type, String label, Span span) implements Symbol {
    }

    /** A function. */
    record Func(String name, Type returnType, List<Type> parameterTypes,
                String label, Span span) implements Symbol {
    }

    /**
     * An enumerator: a name for a number, with no storage at all. It is replaced by
     * its value wherever it appears, which is what lets it size an array or label a
     * case.
     */
    record Constant(String name, int value, Span span) implements Symbol {
    }

    /** The declared type, or the return type for a function. */
    default Type type() {
        return switch (this) {
            case LocalVar v -> v.type();
            case ParamVar v -> v.type();
            case GlobalVar v -> v.type();
            case Func f -> f.returnType();
            case Constant ignored -> Type.WORD;
        };
    }

    /** How this symbol is described in diagnostics. */
    default String kindName() {
        return switch (this) {
            case LocalVar ignored -> "variable";
            case ParamVar ignored -> "parameter";
            case GlobalVar ignored -> "global";
            case Func ignored -> "function";
            case Constant ignored -> "enum constant";
        };
    }
}
