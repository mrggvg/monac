package dev.madlador.parser.ast;

import dev.madlador.diag.Span;

import java.util.Optional;

/**
 * A variable at file scope.
 *
 * <p>The initializer must be a constant, because a global is emitted as a DW or DB
 * directive in the image rather than being assigned at run time — there is no
 * startup code to run initializers in.
 */
public record GlobalDeclaration(TypeRef type, String name,
                                Optional<Expression> initializer,
                                Optional<InitializerList> list, Span span)
        implements TopLevel {
}
