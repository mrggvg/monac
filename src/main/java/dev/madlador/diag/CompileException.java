package dev.madlador.diag;

/**
 * Signals that compilation cannot continue.
 *
 * <p>Thrown only when a pass is genuinely stuck. Everything recoverable goes to
 * {@link DiagnosticReporter} instead, so that a single run reports as many real
 * problems as it can find.
 */
public class CompileException extends RuntimeException {

    public CompileException(String message) {
        super(message);
    }

    public CompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
