package dev.madlador.sema;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns Mona identifiers into labels the target assembler will accept.
 *
 * <p>The assembler is stricter than it looks, and all three of these fail silently
 * or confusingly if ignored:
 * <ul>
 *   <li>labels are uppercased internally, so {@code foo} and {@code Foo} collide;</li>
 *   <li>a label may not spell a register name, so a function called {@code a},
 *       {@code sp} or {@code ch} is rejected;</li>
 *   <li>a label must start with a letter or a dot, so {@code _start} is a syntax
 *       error rather than a label.</li>
 * </ul>
 *
 * <p>Prefixing every user symbol solves the second and third problems outright, and
 * a per-mangler counter disambiguates names that differ only in case.
 */
public final class Mangler {

    private static final String FUNCTION_PREFIX = "m_";
    private static final String GLOBAL_PREFIX = "g_";

    /** Uppercased label -> the mangled label already issued for it. */
    private final Map<String, String> issued = new HashMap<>();
    private int collisionCounter = 0;
    private int internalCounter = 0;

    public String forFunction(String name) {
        return unique(FUNCTION_PREFIX + name);
    }

    public String forGlobal(String name) {
        return unique(GLOBAL_PREFIX + name);
    }

    /**
     * A fresh internal label. Dotted, which the assembler allows, and numbered from
     * a single module-wide counter because the label namespace is global — per
     * function numbering would collide across functions.
     */
    public String internal(String hint) {
        return ".L" + (internalCounter++) + "_" + hint;
    }

    private String unique(String candidate) {
        String key = candidate.toUpperCase(Locale.ROOT);
        String existing = issued.get(key);
        if (existing == null) {
            issued.put(key, candidate);
            return candidate;
        }
        // Two source names that differ only in case would become one label.
        String disambiguated;
        do {
            disambiguated = candidate + "_" + (collisionCounter++);
        } while (issued.containsKey(disambiguated.toUpperCase(Locale.ROOT)));
        issued.put(disambiguated.toUpperCase(Locale.ROOT), disambiguated);
        return disambiguated;
    }
}
