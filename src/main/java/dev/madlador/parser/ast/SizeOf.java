package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/**
 * {@code sizeof(type)} or {@code sizeof(expression)}, folded to a constant.
 *
 * <p>It earns its place because of the heap. {@code __alloc(6)} for a three-word
 * struct is a number that is right until somebody adds a member, and then it is a
 * bug that corrupts the block after it rather than one that fails to compile.
 *
 * <p>Exactly one of the two fields is present. The parser decides which by looking
 * at the token after the parenthesis, which is unambiguous here because a type
 * always starts with a keyword — including {@code struct}.
 */
public record SizeOf(Optional<TypeRef> type, Optional<Expression> operand, Span span)
        implements Expression {

    public static SizeOf ofType(TypeRef type, Span span) {
        return new SizeOf(Optional.of(type), Optional.empty(), span);
    }

    public static SizeOf ofExpression(Expression operand, Span span) {
        return new SizeOf(Optional.empty(), Optional.of(operand), span);
    }
}
