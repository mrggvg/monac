package dev.madlador.sema;

import dev.madlador.parser.ast.Expression;
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.Node;
import dev.madlador.parser.ast.SwitchCase;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * What semantic analysis learned, keyed by AST node identity.
 *
 * <p>Kept beside the tree rather than inside it so the AST records stay plain data
 * and the parser never has to know about types.
 */
public final class SemanticInfo {

    private final Map<Node, Symbol> bindings = new IdentityHashMap<>();
    private final Map<Expression, Type> types = new IdentityHashMap<>();
    private final Map<FunctionDefinition, FrameInfo> frames = new IdentityHashMap<>();
    private final java.util.Set<Symbol> addressTaken =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** Frame layout facts the code generator needs. */
    public record FrameInfo(int localBytes, int slotCount) {
    }

    public void bind(Node node, Symbol symbol) {
        bindings.put(node, symbol);
    }

    public Symbol symbolOf(Node node) {
        return bindings.get(node);
    }

    public void setType(Expression expression, Type type) {
        types.put(expression, type);
    }

    public Type typeOf(Expression expression) {
        Type type = types.get(expression);
        if (type == null) {
            throw new IllegalStateException("no type recorded for " + expression);
        }
        return type;
    }

    /**
     * Records that a variable's address was taken, so it must live in memory.
     * A register allocator has to leave these alone.
     */
    public void markAddressTaken(Symbol symbol) {
        addressTaken.add(symbol);
    }

    public boolean isAddressTaken(Symbol symbol) {
        return addressTaken.contains(symbol);
    }

    /**
     * The constant a {@code sizeof} evaluates to.
     *
     * <p>Recorded here rather than folded in the parser because a size is a fact
     * about a type, and types are what this pass works out.
     */
    public void setSize(Expression expression, int bytes) {
        sizes.put(expression, bytes);
    }

    public int sizeOf(Expression expression) {
        Integer bytes = sizes.get(expression);
        if (bytes == null) throw new IllegalStateException("no size recorded for " + expression);
        return bytes;
    }

    private final Map<Expression, Integer> sizes = new IdentityHashMap<>();

    /** The recorded size, or null — for folding, which asks without knowing. */
    public Integer sizeIfRecorded(Expression expression) {
        return sizes.get(expression);
    }

    /** The recorded type, or null — for folding, which asks without knowing. */
    public Type typeIfRecorded(Expression expression) {
        return types.get(expression);
    }

    private final Map<Symbol, Integer> constantValues = new IdentityHashMap<>();

    /**
     * Records that a {@code const} variable's initializer was a constant, so that it
     * may be used where one is needed — an array length, a case label, another
     * constant — as C++ and C23's {@code constexpr} allow. An enumerator's value lives
     * on its symbol and is not repeated here.
     */
    public void setConstantValue(Symbol symbol, int value) {
        constantValues.put(symbol, value & 0xFFFF);
    }

    private final Map<Symbol, Integer> constantAddresses = new IdentityHashMap<>();

    /**
     * Records that a {@code const} pointer was initialized with a constant address,
     * such as {@code byte* const SCREEN = 4096}. Kept apart from
     * {@link #setConstantValue} on purpose: that one makes a name usable where a
     * constant expression is required, and the folder that evaluates those does
     * integer arithmetic, not pointer arithmetic. This one is only for lowering, which
     * reads the pointer as an immediate instead of loading it.
     */
    public void setConstantAddress(Symbol symbol, int address) {
        constantAddresses.put(symbol, address & 0xFFFF);
    }

    /**
     * The value a read of {@code symbol} can be replaced with: a constant, or a const
     * pointer's constant address.
     */
    public java.util.Optional<Integer> knownValueOf(Symbol symbol) {
        java.util.Optional<Integer> constant = constantValueOf(symbol);
        return constant.isPresent() ? constant
                : java.util.Optional.ofNullable(constantAddresses.get(symbol));
    }

    /** The compile-time value a symbol stands for, if it stands for one. */
    public java.util.Optional<Integer> constantValueOf(Symbol symbol) {
        if (symbol instanceof Symbol.Constant constant) {
            return java.util.Optional.of(constant.value());
        }
        return java.util.Optional.ofNullable(constantValues.get(symbol));
    }

    /**
     * One value an aggregate's initializer stores: at a byte offset into the object,
     * as a scalar type, from an expression — or, for the bytes of a string, from a
     * number already known, with no expression at all.
     */
    public record InitEntry(int offset, Type type, Expression value, int literal) {
        public boolean isLiteral() {
            return value == null;
        }
    }

    private final Map<Node, java.util.List<InitEntry>> initializations = new IdentityHashMap<>();

    /**
     * Where each value of a declaration's initializer goes, worked out once by analysis
     * so that lowering — a global's image or a local's stores — only follows it.
     */
    public void setInitialization(Node declaration, java.util.List<InitEntry> entries) {
        initializations.put(declaration, java.util.List.copyOf(entries));
    }

    /** The layout of a declaration's initializer, or null when it has none recorded. */
    public java.util.List<InitEntry> initializationOf(Node declaration) {
        return initializations.get(declaration);
    }

    private final Map<SwitchCase, Integer> caseValues = new IdentityHashMap<>();

    /** What a case label came to. Recorded here because the label may name a constant. */
    public void setCaseValue(SwitchCase arm, int value) {
        caseValues.put(arm, value);
    }

    public int caseValueOf(SwitchCase arm) {
        Integer value = caseValues.get(arm);
        if (value == null) throw new IllegalStateException("no value recorded for a case label");
        return value;
    }

    public void setFrame(FunctionDefinition function, FrameInfo info) {
        frames.put(function, info);
    }

    public FrameInfo frameOf(FunctionDefinition function) {
        return frames.get(function);
    }
}
