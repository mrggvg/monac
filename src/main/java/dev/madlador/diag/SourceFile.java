package dev.madlador.diag;

import java.util.ArrayList;
import java.util.List;

/**
 * A source file plus the line index needed to turn byte offsets into positions.
 *
 * <p>Line starts are computed once up front; the offset&nbsp;-&gt; (line, column)
 * conversion is a binary search done lazily, only when a diagnostic is actually
 * rendered.
 */
public final class SourceFile {

    private final String name;
    private final String text;
    private final int[] lineStarts;

    public SourceFile(String name, String text) {
        this.name = name;
        this.text = text;
        this.lineStarts = computeLineStarts(text);
    }

    private static int[] computeLineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                starts.add(i + 1);
            } else if (c == '\r') {
                // Treat CR and CRLF as one line break.
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) result[i] = starts.get(i);
        return result;
    }

    public String name() {
        return name;
    }

    public String text() {
        return text;
    }

    public int length() {
        return text.length();
    }

    /** 1-based line number containing {@code offset}. */
    public int lineOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= clamped) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    /** 1-based column number of {@code offset} within its line. */
    public int columnOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        return clamped - lineStarts[lineOf(clamped) - 1] + 1;
    }

    /** The text of a 1-based line number, without its terminator. */
    public String lineText(int line) {
        if (line < 1 || line > lineStarts.length) return "";
        int start = lineStarts[line - 1];
        int end = (line < lineStarts.length) ? lineStarts[line] : text.length();
        while (end > start && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) end--;
        return text.substring(start, end);
    }

    /** The source text a span covers. */
    public String textOf(Span span) {
        if (span.isNone()) return "";
        int start = Math.max(0, Math.min(span.start(), text.length()));
        int end = Math.max(start, Math.min(span.end(), text.length()));
        return text.substring(start, end);
    }

    /** e.g. {@code simple.mona:2:16}. */
    public String positionOf(Span span) {
        if (span.isNone()) return name;
        return name + ":" + lineOf(span.start()) + ":" + columnOf(span.start());
    }
}
