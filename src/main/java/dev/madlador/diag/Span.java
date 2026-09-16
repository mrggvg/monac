package dev.madlador.diag;

/**
 * A half-open range of byte offsets into a source file.
 *
 * <p>Deliberately stores offsets only, never line/column. Converting to a human
 * position is {@link SourceFile}'s job, so that tab handling and multi-byte
 * characters are dealt with in exactly one place.
 *
 * @param start inclusive start offset
 * @param end   exclusive end offset
 */
public record Span(int start, int end) {

    /** Used for nodes the compiler synthesises, which correspond to no source text. */
    public static final Span NONE = new Span(-1, -1);

    public Span {
        if (start > end && !(start == -1 && end == -1)) {
            throw new IllegalArgumentException("span start " + start + " is after end " + end);
        }
    }

    public int length() {
        return end - start;
    }

    public boolean isNone() {
        return start < 0;
    }

    /** The smallest span covering both operands. */
    public Span to(Span other) {
        if (isNone()) return other;
        if (other.isNone()) return this;
        return new Span(Math.min(start, other.start), Math.max(end, other.end));
    }

    @Override
    public String toString() {
        return isNone() ? "<none>" : start + ".." + end;
    }
}
