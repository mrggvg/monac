package dev.madlador.diag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Collects diagnostics instead of throwing them, so one run can report many
 * problems rather than stopping at the first.
 *
 * <p>A pass that genuinely cannot continue throws {@link CompileException}; anything
 * recoverable is reported here and compilation carries on to find more.
 */
public final class DiagnosticReporter {

    private final SourceFile source;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private boolean warningsAreErrors;

    public DiagnosticReporter(SourceFile source) {
        this.source = source;
    }

    public SourceFile source() {
        return source;
    }

    public void setWarningsAreErrors(boolean value) {
        this.warningsAreErrors = value;
    }

    public void report(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public void error(Span span, String message) {
        report(Diagnostic.error(span, message));
    }

    public void error(Span span, String message, String note) {
        report(Diagnostic.error(span, message, note));
    }

    public void warning(Span span, String message) {
        report(Diagnostic.warning(span, message));
    }

    public void warning(Span span, String message, String note) {
        report(Diagnostic.warning(span, message, note));
    }

    public List<Diagnostic> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR)
                || (warningsAreErrors && !diagnostics.isEmpty());
    }

    public int errorCount() {
        return (int) diagnostics.stream()
                .filter(d -> d.severity() == Diagnostic.Severity.ERROR)
                .count();
    }

    /** All diagnostics in source order, rendered for the terminal. */
    public String render() {
        List<Diagnostic> sorted = new ArrayList<>(diagnostics);
        sorted.sort(Comparator.comparingInt(d -> d.span().isNone() ? Integer.MAX_VALUE : d.span().start()));
        StringBuilder sb = new StringBuilder();
        for (Diagnostic d : sorted) sb.append(d.render(source));
        return sb.toString();
    }

    /** Stops compilation when the current phase has produced errors. */
    public void throwIfErrors() {
        if (hasErrors()) throw new CompileException(errorCount() + " error(s)");
    }
}
