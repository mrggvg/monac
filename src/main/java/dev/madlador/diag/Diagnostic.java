package dev.madlador.diag;

/**
 * One compiler message, anchored at a source span.
 *
 * @param severity how bad it is
 * @param span     where in the source it applies
 * @param message  the primary message, lowercase and without trailing punctuation
 * @param note     optional supplementary explanation, or null
 */
public record Diagnostic(Severity severity, Span span, String message, String note) {

    public enum Severity {
        ERROR("error"),
        WARNING("warning"),
        NOTE("note");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static Diagnostic error(Span span, String message) {
        return new Diagnostic(Severity.ERROR, span, message, null);
    }

    public static Diagnostic error(Span span, String message, String note) {
        return new Diagnostic(Severity.ERROR, span, message, note);
    }

    public static Diagnostic warning(Span span, String message) {
        return new Diagnostic(Severity.WARNING, span, message, null);
    }

    public static Diagnostic warning(Span span, String message, String note) {
        return new Diagnostic(Severity.WARNING, span, message, note);
    }

    /**
     * Renders in the usual compiler style:
     *
     * <pre>
     * simple.mona:2:16: error: undeclared identifier 'y'
     *     return x + y;
     *                ^
     * </pre>
     */
    public String render(SourceFile source) {
        StringBuilder sb = new StringBuilder();
        sb.append(source.positionOf(span)).append(": ")
          .append(severity.label()).append(": ").append(message).append('\n');

        if (!span.isNone()) {
            int line = source.lineOf(span.start());
            int column = source.columnOf(span.start());
            String lineText = source.lineText(line);
            sb.append("    ").append(lineText).append('\n');

            StringBuilder caret = new StringBuilder("    ");
            for (int i = 0; i < column - 1 && i < lineText.length(); i++) {
                caret.append(lineText.charAt(i) == '\t' ? '\t' : ' ');
            }
            caret.append('^');
            // Underline the rest of the span, but never past the end of this line.
            int lineEndOffset = source.lineText(line).length() + column;
            int underline = Math.min(span.length(), Math.max(0, lineEndOffset - column)) - 1;
            for (int i = 0; i < underline; i++) caret.append('~');
            sb.append(caret).append('\n');
        }

        if (note != null) {
            sb.append("    note: ").append(note).append('\n');
        }
        return sb.toString();
    }
}
