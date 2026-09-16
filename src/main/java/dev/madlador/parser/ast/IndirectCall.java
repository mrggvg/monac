package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.List;

/**
 * A call through an expression rather than a name: {@code (*fp)(x)},
 * {@code handlers[i](x)}, {@code button->press()}.
 *
 * <p>A call by name stays a {@link Call}, even when the name is a variable holding a
 * function pointer — analysis tells the two apart, because only it knows what the name
 * is.
 */
public record IndirectCall(Expression callee, List<Expression> arguments, Span span)
        implements Expression {
}
