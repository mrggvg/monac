package dev.madlador.sema;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A lexical scope: a name table with a parent pointer.
 *
 * <p>Nesting follows C rules — an inner declaration shadows an outer one, and
 * redeclaring a name in the <em>same</em> scope is an error.
 */
public final class Scope {

    private final Scope parent;
    private final Map<String, Symbol> symbols = new LinkedHashMap<>();

    public Scope(Scope parent) {
        this.parent = parent;
    }

    public Scope parent() {
        return parent;
    }

    /** Looks up a name here and then outward. */
    public Symbol resolve(String name) {
        for (Scope scope = this; scope != null; scope = scope.parent) {
            Symbol symbol = scope.symbols.get(name);
            if (symbol != null) return symbol;
        }
        return null;
    }

    /** Looks up a name in this scope only, for redeclaration checks. */
    public Symbol resolveLocally(String name) {
        return symbols.get(name);
    }

    /** Declares a name, returning false if this scope already has one. */
    public boolean declare(Symbol symbol) {
        return symbols.putIfAbsent(symbol.name(), symbol) == null;
    }

    public Iterable<Symbol> symbols() {
        return symbols.values();
    }
}
