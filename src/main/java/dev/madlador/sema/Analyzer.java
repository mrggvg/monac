package dev.madlador.sema;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.parser.ast.Assign;
import dev.madlador.parser.ast.Binary;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.Block;
import dev.madlador.parser.ast.BlockItem;
import dev.madlador.parser.ast.Constant;
import dev.madlador.parser.ast.Conditional;
import dev.madlador.parser.ast.Declaration;
import dev.madlador.parser.ast.Expression;
import dev.madlador.parser.ast.AddressOf;
import dev.madlador.parser.ast.Break;
import dev.madlador.parser.ast.Deref;
import dev.madlador.parser.ast.Index;
import dev.madlador.parser.ast.StringLiteral;
import dev.madlador.parser.ast.Switch;
import dev.madlador.parser.ast.SwitchCase;
import dev.madlador.parser.ast.Call;
import dev.madlador.parser.ast.Cast;
import dev.madlador.parser.ast.GlobalDeclaration;
import dev.madlador.parser.ast.TopLevel;
import dev.madlador.parser.ast.Continue;
import dev.madlador.parser.ast.Empty;
import dev.madlador.parser.ast.ExpressionStatement;
import dev.madlador.parser.ast.For;
import dev.madlador.parser.ast.If;
import dev.madlador.parser.ast.While;
import dev.madlador.parser.ast.FunctionDeclaration;
import dev.madlador.parser.ast.TypeRef;
import dev.madlador.diag.Span;
import dev.madlador.parser.ast.EnumDeclaration;
import dev.madlador.parser.ast.Enumerator;
import dev.madlador.parser.ast.Initializer;
import dev.madlador.parser.ast.InitializerList;
import dev.madlador.parser.ast.Node;
import dev.madlador.parser.ast.Goto;
import dev.madlador.parser.ast.Labeled;
import dev.madlador.parser.ast.IndirectCall;
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.Identifier;
import dev.madlador.parser.ast.Logical;
import dev.madlador.parser.ast.Parameter;
import dev.madlador.parser.ast.Member;
import dev.madlador.parser.ast.PostfixUpdate;
import dev.madlador.parser.ast.Program;
import dev.madlador.parser.ast.SizeOf;
import dev.madlador.parser.ast.StructDeclaration;
import dev.madlador.parser.ast.Return;
import dev.madlador.parser.ast.Statement;
import dev.madlador.parser.ast.Unary;
import dev.madlador.parser.ast.Comma;
import dev.madlador.parser.ast.DoWhile;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Name resolution and type checking.
 *
 * <p>Replaces the old arrangement where the only symbol table was a
 * {@code HashMap} living inside the code generator and populated while emitting.
 * That had two consequences worth stating: parameters were never registered, so a
 * function that used its arguments emitted no code for them at all; and an
 * undeclared variable produced <em>silence</em> rather than an error.
 */
public final class Analyzer {

    private final DiagnosticReporter reporter;
    private final Mangler mangler;
    private final SemanticInfo info = new SemanticInfo();

    private Scope scope;
    private FunctionDefinition currentFunction;

    /** Variables declared {@code const} at their top level: they may not be assigned. */
    private final Set<Symbol> readOnly = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Every enum tag, so that {@code enum Name} as a type can be checked. */
    private final Set<String> enumTags = new HashSet<>();

    /** Array lengths written as expressions, each worked out once. */
    private final Map<Expression, Integer> computedLengths = new IdentityHashMap<>();

    /** Where a label or a goto sits: every enclosing block, and which of its items. */
    private record JumpSite(String label, dev.madlador.diag.Span span, List<Object> blocks,
                            List<Integer> indices) {
    }

    private final List<Object> openBlocks = new ArrayList<>();
    private final List<Integer> openIndices = new ArrayList<>();
    private final Map<String, JumpSite> labels = new java.util.HashMap<>();
    private final List<JumpSite> gotos = new ArrayList<>();

    /** Functions with a body somewhere in the file, as opposed to only a prototype. */
    private final Set<String> functionBodies = new HashSet<>();

    /** Definitions seen so far, to catch a second one; not what a use is judged by. */
    private final Set<String> definedFunctions = new HashSet<>();
    private int nextSlot;
    private int localBytes;
    private boolean sawReturnWithValue;
    private int loopDepth;
    private int switchDepth;

    private final StructTable structs;

    public Analyzer(DiagnosticReporter reporter, Mangler mangler) {
        this.reporter = reporter;
        this.mangler = mangler;
        this.structs = new StructTable(reporter, new StructTable.Names() {
            @Override
            public int arrayLength(Expression length) {
                return computedLength(length);
            }

            @Override
            public boolean isEnumTag(String tag) {
                return enumTags.contains(tag);
            }
        });
    }

    /**
     * The semantic type for a written one.
     *
     * <p>Everywhere that used {@code Type.of} now comes here instead, because a type
     * can name a struct and only the table knows what that is.
     */
    private Type resolve(dev.madlador.parser.ast.TypeRef ref) {
        return structs.resolve(ref);
    }

    public SemanticInfo analyze(Program program) {
        scope = new Scope(null);

        // Which functions have bodies, before anything is looked at: a global's
        // initializer is analysed where it stands and may name a function defined
        // further down, as a table of handlers or a pointer set at startup does.
        for (TopLevel item : program.items()) {
            if (item instanceof FunctionDefinition definition) functionBodies.add(definition.name());
        }

        // Enumerations first: their constants may size a struct's array member or a
        // global's, and every later declaration is allowed to use them.
        for (TopLevel item : program.items()) {
            if (item instanceof EnumDeclaration enumeration) declareEnum(enumeration);
        }

        // Then structs: a function signature or a global may be of struct type, so
        // every layout has to be known before any of them is looked at.
        structs.build(program);

        // Then two passes over the top level. Declaring every name first is what
        // makes a call to a function defined later in the file work, and mutual
        // recursion possible at all.
        declareTopLevel(program);

        boolean sawMain = false;
        for (TopLevel item : program.items()) {
            switch (item) {
                case FunctionDefinition f -> {
                    if (f.name().equals("main")) sawMain = true;
                    analyzeFunction(f);
                }
                case FunctionDeclaration ignored -> { }   // checked when it was declared
                case GlobalDeclaration ignored -> { }   // analysed in order, as declared
                case StructDeclaration ignored -> { }   // laid out before this pass
                case EnumDeclaration ignored -> { }     // declared before the structs
            }
        }

        if (!sawMain) {
            reporter.error(program.span(), "no function named 'main'",
                    "execution starts by calling main");
        }
        return info;
    }

    private void declareTopLevel(Program program) {
        for (TopLevel item : program.items()) {
            switch (item) {
                case FunctionDefinition f -> {
                    Symbol.Func symbol = declareFunction(
                            f.name(), f.returnType(), f.parameters(), f.span());
                    if (!definedFunctions.add(f.name())) {
                        reporter.error(f.span(), "'" + f.name() + "' is already defined");
                    }
                    info.bind(f, symbol);
                    if (f.name().equals("main") && !f.parameters().isEmpty()) {
                        reporter.error(f.span(), "'main' may not take parameters",
                                "the entry stub calls it with no arguments");
                    }
                }
                case GlobalDeclaration g -> {
                    Symbol.GlobalVar symbol = new Symbol.GlobalVar(
                            g.name(), declaredType(g.type(), g.initializer(), g.list(), g.span()),
                            mangler.forGlobal(g.name()), g.span());
                    if (!scope.declare(symbol)) {
                        reporter.error(g.span(), "'" + g.name() + "' is already declared");
                    }
                    info.bind(g, symbol);
                    if (g.type().isConst()) readOnly.add(symbol);
                    // Now rather than in the next pass, so that a const declared here
                    // can size the arrays and label the cases that follow it.
                    analyzeGlobalInitializer(g);
                }
                case FunctionDeclaration d ->
                        declareFunction(d.name(), d.returnType(), d.parameters(), d.span());
                case StructDeclaration ignored -> { }   // a type, not a name to bind
                case EnumDeclaration ignored -> { }     // declared before the structs
            }
        }
    }

    /**
     * Declares a function for a definition or a prototype. They may come in either
     * order and any number of times, but must all agree: the first creates the symbol
     * and every later one is checked against it. That keeps a function to exactly one
     * symbol and one label — the mangler hands out a fresh label on every call, so
     * declaring twice would leave calls pointing at a label nothing defines.
     */
    private Symbol.Func declareFunction(String name, TypeRef returnType,
                                        List<Parameter> parameters, Span span) {
        Type resolvedReturn = resolve(returnType);
        List<Type> parameterTypes = parameters.stream().map(p -> resolve(p.type())).toList();

        if (scope.resolve(name) instanceof Symbol.Func earlier) {
            if (!earlier.returnType().equals(resolvedReturn)
                    || !earlier.parameterTypes().equals(parameterTypes)) {
                reporter.error(span, "conflicting types for '" + name + "'",
                        "it was first declared as " + signature(earlier));
            }
            return earlier;
        }
        Symbol.Func symbol = new Symbol.Func(
                name, resolvedReturn, parameterTypes, mangler.forFunction(name), span);
        if (!scope.declare(symbol)) {
            reporter.error(span, "'" + name + "' is already declared");
        }
        return symbol;
    }

    private static String signature(Symbol.Func function) {
        StringBuilder text = new StringBuilder(function.returnType().display())
                .append(' ').append(function.name()).append('(');
        for (int i = 0; i < function.parameterTypes().size(); i++) {
            if (i > 0) text.append(", ");
            text.append(function.parameterTypes().get(i).display());
        }
        return text.append(')').toString();
    }

    /**
     * A prototype with no definition behind it is harmless until something calls it,
     * and then there is no code to call. C finds that at link time; with the whole
     * program in one file it can be said here, at the call.
     */
    private void requireDefinition(Symbol.Func function, Span use) {
        if (!functionBodies.contains(function.name())) {
            reporter.error(use, "'" + function.name() + "' is declared but never defined",
                    "a prototype promises a definition somewhere in the file");
        }
    }

    /**
     * A global's initializer has to fold to a constant: globals are emitted as DW
     * or DB directives in the image, and there is no startup code that could run an
     * expression.
     */
    private void analyzeGlobalInitializer(GlobalDeclaration global) {
        Symbol symbol = info.symbolOf(global);
        Type declared = symbol.type();
        boolean constant = global.type().isConst() && declared.isInteger();
        boolean constantPointer = global.type().isConst() && declared.isPointer();
        if (global.initializer().isEmpty() && global.list().isEmpty()) {
            // A global starts as zero, so a const one without an initializer is 0.
            if (constant) info.setConstantValue(symbol, 0);
            if (constantPointer) info.setConstantAddress(symbol, 0);
            return;
        }
        if (layOutInitializer(global, declared, global.initializer(), global.list())) {
            requireConstantEntries(info.initializationOf(global), "a global initializer");
            return;
        }
        Expression init = global.initializer().get();
        Type valueType = analyzeExpression(init);
        checkAssignable(declared, valueType, init);
        // One entry, so that lowering builds every global's image the same way.
        List<SemanticInfo.InitEntry> single = List.of(new SemanticInfo.InitEntry(0, declared, init, 0));
        info.setInitialization(global, single);
        requireConstantEntries(single, "a global initializer");
        if (constant) fold(init).ifPresent(v -> info.setConstantValue(symbol, stored(declared, v)));
        if (constantPointer) fold(init).ifPresent(v -> info.setConstantAddress(symbol, v));
    }


    private void analyzeFunction(FunctionDefinition function) {
        currentFunction = function;
        labels.clear();
        gotos.clear();
        nextSlot = 0;
        localBytes = 0;
        sawReturnWithValue = false;
        loopDepth = 0;
        switchDepth = 0;

        scope = new Scope(scope);

        checkNotStructByValue(resolve(function.returnType()), function.span(),
                "a function cannot return");

        int index = 0;
        for (Parameter parameter : function.parameters()) {
            Type type = resolve(parameter.type());
            checkNotStructByValue(type, parameter.span(), "a parameter cannot be");
            // Argument 0 comes in a register, so unlike the rest it has nowhere on the
            // stack to be read from and needs a frame slot of its own. Whether that
            // slot survives is the optimizer's business: a parameter that can stay in
            // a register is promoted out of it like any other value.
            int slot = index == 0 ? reserveSlot(type) : Symbol.ParamVar.NO_SLOT;
            Symbol.ParamVar symbol = new Symbol.ParamVar(
                    parameter.name(), type, index++, slot, parameter.span());
            if (!scope.declare(symbol)) {
                reporter.error(parameter.span(),
                        "duplicate parameter '" + parameter.name() + "'");
            }
            info.bind(parameter, symbol);
            if (parameter.type().isConst()) readOnly.add(symbol);
        }

        analyzeBlock(function.body(), false);

        Type returnType = resolve(function.returnType());
        if (!returnType.isVoid() && !sawReturnWithValue) {
            reporter.warning(function.span(),
                    "function '" + function.name() + "' may finish without returning a value",
                    "a value-less exit returns 0");
        }

        checkJumps();
        info.setFrame(function, new SemanticInfo.FrameInfo(localBytes, nextSlot));
        scope = scope.parent();
        currentFunction = null;
    }

    private void analyzeBlock(Block block, boolean ownScope) {
        if (ownScope) scope = new Scope(scope);
        analyzeItems(block, block.items());
        if (ownScope) scope = scope.parent();
    }

    /**
     * The items of a block or a switch arm, keeping track of which one is being looked
     * at — which is what a goto is judged by.
     */
    private void analyzeItems(Object owner, List<BlockItem> items) {
        openBlocks.add(owner);
        openIndices.add(0);
        for (int i = 0; i < items.size(); i++) {
            openIndices.set(openIndices.size() - 1, i);
            analyzeBlockItem(items.get(i));
        }
        openBlocks.remove(openBlocks.size() - 1);
        openIndices.remove(openIndices.size() - 1);
    }

    private void analyzeBlockItem(BlockItem item) {
        switch (item) {
            case Declaration d -> analyzeDeclaration(d);
            case EnumDeclaration e -> declareEnum(e);
            case Statement s -> analyzeStatement(s);
        }
    }

    private void analyzeDeclaration(Declaration declaration) {
        Type type = declaredType(declaration.type(), declaration.initializer(),
                declaration.list(), declaration.span());

        // The initializer is analysed before the name is in scope, so
        // `word x = x;` reports an undeclared identifier rather than reading itself.
        boolean aggregate = layOutInitializer(declaration, type,
                declaration.initializer(), declaration.list());
        if (!aggregate) {
            declaration.initializer().ifPresent(init -> {
                Type valueType = analyzeExpression(init);
                checkAssignable(type, valueType, init);
            });
        }

        if (scope.resolveLocally(declaration.name()) != null) {
            reporter.error(declaration.span(),
                    "'" + declaration.name() + "' is already declared in this scope");
        }

        boolean isConst = declaration.type().isConst();
        Symbol symbol;
        if (declaration.isStatic()) {
            // A global that only this block can name. The label carries the function's
            // name so two functions' `static word count` stay two variables.
            symbol = new Symbol.GlobalVar(declaration.name(), type,
                    mangler.forGlobal(currentFunction.name() + "_" + declaration.name()),
                    declaration.span());
            if (!aggregate && declaration.initializer().isPresent()) {
                info.setInitialization(declaration, List.of(new SemanticInfo.InitEntry(
                        0, type, declaration.initializer().get(), 0)));
            }
            List<SemanticInfo.InitEntry> plan = info.initializationOf(declaration);
            if (plan != null) requireConstantEntries(plan, "a static variable's initializer");
        } else {
            symbol = new Symbol.LocalVar(
                    declaration.name(), type, reserveSlot(type), declaration.span());
            if (isConst && declaration.initializer().isEmpty() && declaration.list().isEmpty()) {
                reporter.error(declaration.span(),
                        "'" + declaration.name() + "' is const, so it needs an initializer",
                        "nothing could ever give it a value afterwards");
            }
        }
        scope.declare(symbol);
        info.bind(declaration, symbol);

        if (isConst) {
            readOnly.add(symbol);
            if (type.isInteger() || type.isPointer()) {
                Optional<Integer> value = declaration.initializer().isPresent()
                        ? fold(declaration.initializer().get())
                        : declaration.isStatic() ? Optional.of(0) : Optional.empty();
                if (type.isInteger()) {
                    value.ifPresent(v -> info.setConstantValue(symbol, stored(type, v)));
                } else {
                    value.ifPresent(v -> info.setConstantAddress(symbol, v));
                }
            }
        }
    }

    /**
     * Reserves frame space for a variable and returns the slot that anchors it.
     *
     * <p>An array or a struct occupies several slots. Slot offsets grow more negative
     * with the index, so the slot whose offset is the LOWEST address is the last one
     * reserved — and that is the variable's base, letting member or element k sit at
     * base + k with ascending addresses.
     */
    private int reserveSlot(Type type) {
        int slotsNeeded = Math.max(1, (type.slotSize() + 1) / 2);
        int baseSlot = nextSlot + slotsNeeded - 1;
        nextSlot += slotsNeeded;
        localBytes += slotsNeeded * 2;
        return baseSlot;
    }

    private void analyzeStatement(Statement statement) {
        switch (statement) {
            case Block b -> analyzeBlock(b, true);
            case Return r -> analyzeReturn(r);
            case ExpressionStatement e -> analyzeExpression(e.expression());
            case If s -> {
                checkCondition(s.condition());
                analyzeStatement(s.thenBranch());
                s.elseBranch().ifPresent(this::analyzeStatement);
            }
            case While s -> {
                checkCondition(s.condition());
                loopDepth++;
                analyzeStatement(s.body());
                loopDepth--;
            }
            case DoWhile s -> {
                loopDepth++;
                analyzeStatement(s.body());
                loopDepth--;
                checkCondition(s.condition());
            }
            case For s -> analyzeFor(s);
            case Break s -> {
                if (loopDepth == 0 && switchDepth == 0) {
                    reporter.error(s.span(), "'break' outside of a loop or switch");
                }
            }
            case Continue s -> {
                if (loopDepth == 0) {
                    reporter.error(s.span(), "'continue' outside of a loop");
                }
            }
            case Empty ignored -> { }
            case Switch sw -> analyzeSwitch(sw);
            case Goto g -> gotos.add(new JumpSite(g.label(), g.span(),
                    List.copyOf(openBlocks), List.copyOf(openIndices)));
            case Labeled l -> {
                if (labels.containsKey(l.label())) {
                    reporter.error(l.span(), "label '" + l.label() + "' is already defined in this function");
                } else {
                    labels.put(l.label(), new JumpSite(l.label(), l.span(),
                            List.copyOf(openBlocks), List.copyOf(openIndices)));
                }
                analyzeStatement(l.body());
            }
        }
    }

    /**
     * A switch introduces a break target but no continue target, and its arms share
     * one scope — a declaration in one arm is visible in the next, as in C.
     */
    private void analyzeSwitch(Switch statement) {
        checkCondition(statement.subject());

        Set<Integer> seen = new HashSet<>();
        boolean sawDefault = false;
        scope = new Scope(scope);
        switchDepth++;

        for (SwitchCase arm : statement.cases()) {
            if (arm.isDefault()) {
                if (sawDefault) {
                    reporter.error(arm.span(), "a switch may have only one 'default'");
                }
                sawDefault = true;
            } else {
                // Folded here rather than in the parser, because a label may name a
                // constant — `case RED:` — which only analysis can look up.
                analyzeExpression(arm.label());
                Optional<Integer> value = fold(arm.label());
                if (value.isEmpty()) {
                    reporter.error(arm.label().span(), "a case label must be a constant expression");
                }
                info.setCaseValue(arm, value.orElse(0));
                if (value.isPresent() && !seen.add(value.get())) {
                    reporter.error(arm.span(), "duplicate case label " + value.get());
                }
            }
            analyzeItems(arm, arm.body());
        }

        switchDepth--;
        scope = scope.parent();
    }

    private void analyzeFor(For statement) {
        // A declaration in the initializer belongs to the loop, not the enclosing
        // block, so `for (word i = 0; ...)` does not leak `i`.
        scope = new Scope(scope);

        statement.initializer().ifPresent(item -> {
            switch (item) {
                case Declaration d -> analyzeDeclaration(d);
                case EnumDeclaration e -> declareEnum(e);
                case Statement s -> analyzeStatement(s);
            }
        });
        statement.condition().ifPresent(this::checkCondition);
        statement.update().ifPresent(this::analyzeExpression);

        loopDepth++;
        analyzeStatement(statement.body());
        loopDepth--;

        scope = scope.parent();
    }

    /** A condition must be something that can be compared against zero. */
    private void checkCondition(Expression condition) {
        Type type = analyzeExpression(condition);
        if (!type.isInteger() && !type.isPointer()) {
            reporter.error(condition.span(),
                    "a condition must be a scalar, got '" + type.display() + "'");
        }
    }

    private void analyzeReturn(Return statement) {
        Type declared = resolve(currentFunction.returnType());

        if (statement.value().isPresent()) {
            Type actual = analyzeExpression(statement.value().get());
            if (declared.isVoid()) {
                reporter.error(statement.span(),
                        "cannot return a value from a function returning 'void'");
            } else {
                checkAssignable(declared, actual, statement.value().get());
                sawReturnWithValue = true;
            }
        } else if (!declared.isVoid()) {
            reporter.error(statement.span(),
                    "'return' with no value in a function returning '" + declared.display() + "'");
        }
    }

    /* ---------------- expressions ---------------- */

    private Type analyzeExpression(Expression expression) {
        Type type = switch (expression) {
            case Constant ignored -> Type.WORD;
            case Identifier id -> analyzeIdentifier(id);
            case StringLiteral ignored -> new Type.Ptr(Type.BYTE);
            case Call c -> analyzeCall(c);
            case IndirectCall c -> analyzeIndirectCall(c);
            case AddressOf a -> analyzeAddressOf(a);
            case Deref d -> analyzeDeref(d);
            case Index i -> analyzeIndex(i);
            case Member m -> analyzeMember(m);
            case SizeOf z -> analyzeSizeOf(z);
            case PostfixUpdate u -> analyzePostfixUpdate(u);
            case Cast c -> analyzeCast(c);
            case Conditional c -> analyzeConditional(c);
            case Unary u -> analyzeUnary(u);
            case Binary b -> analyzeBinary(b);
            case Logical l -> analyzeLogical(l);
            case Assign a -> analyzeAssign(a);
            case Comma c -> analyzeComma(c);
        };
        info.setType(expression, type);
        return type;
    }

    /** `a, b` has the type of `b`; `a` is evaluated only for its effects. */
    private Type analyzeComma(Comma comma) {
        analyzeExpression(comma.left());
        return analyzeExpression(comma.right());
    }

    private Type analyzeIdentifier(Identifier identifier) {
        Symbol symbol = scope.resolve(identifier.name());
        if (symbol == null) {
            reporter.error(identifier.span(), "undeclared identifier '" + identifier.name() + "'");
            return Type.WORD;
        }
        if (symbol instanceof Symbol.Func function) {
            // A function's name is its address, as in C: `op = add` and `op = &add`
            // are the same assignment.
            info.bind(identifier, function);
            requireDefinition(function, identifier.span());
            return new Type.Ptr(functionType(function));
        }
        info.bind(identifier, symbol);
        return symbol.type();
    }

    private Type analyzeAddressOf(AddressOf expression) {
        // The address of a function is a pointer to it, with its signature — which is
        // what lets a call through it be checked. An integer still takes one without a
        // cast, so `__setisr(&onKey)` and `word h = &onKey` read as they always did.
        if (expression.operand() instanceof Identifier id
                && scope.resolve(id.name()) instanceof Symbol.Func function) {
            info.bind(id, function);
            info.setType(id, functionType(function));
            requireDefinition(function, expression.span());
            return new Type.Ptr(functionType(function));
        }

        Type operand = analyzeExpression(expression.operand());
        if (!isAssignablePlace(expression.operand())) {
            reporter.error(expression.span(), "'&' needs something with an address");
            return new Type.Ptr(Type.WORD);
        }
        // Taking the address of a local forces it into memory rather than a
        // register, which matters once a register allocator exists.
        markAddressTaken(expression.operand());
        // The address of something const may not be written through either.
        return new Type.Ptr(operand, isReadOnly(expression.operand()));
    }

    private Type analyzeDeref(Deref expression) {
        Type operand = analyzeExpression(expression.operand());
        Type pointee = operand.pointee();
        if (pointee == null) {
            reporter.error(expression.span(),
                    "cannot dereference '" + operand.display() + "'");
            return Type.WORD;
        }
        if (pointee.isVoid()) {
            reporter.error(expression.span(), "cannot dereference a 'void*'");
            return Type.WORD;
        }
        return pointee;
    }

    private Type analyzeIndex(Index expression) {
        Type base = analyzeExpression(expression.base());
        Type index = analyzeExpression(expression.index());

        if (!index.isInteger()) {
            reporter.error(expression.index().span(),
                    "a subscript must be an integer, got '" + index.display() + "'");
        }
        Type element = base.pointee();
        if (element == null) {
            reporter.error(expression.span(),
                    "'" + base.display() + "' cannot be subscripted");
            return Type.WORD;
        }
        return element;
    }

    /**
     * {@code s.field} and {@code p->field}.
     *
     * <p>The two are kept apart rather than treating {@code .} as working on pointers
     * too, because the mistake is so common that a diagnostic naming the fix is worth
     * more than the convenience of accepting both.
     */
    private Type analyzeMember(Member expression) {
        Type base = analyzeExpression(expression.base());

        Type.Struct struct;
        if (expression.throughPointer()) {
            Type pointee = base.pointee();
            struct = pointee == null ? null : pointee.asStruct();
            if (struct == null) {
                reporter.error(expression.span(),
                        "'->' needs a pointer to a struct, got '" + base.display() + "'",
                        base.asStruct() != null
                                ? "'" + base.display() + "' is a struct, not a pointer; use '.'"
                                : null);
                return Type.WORD;
            }
        } else {
            struct = base.asStruct();
            if (struct == null) {
                Type pointee = base.pointee();
                boolean pointsAtStruct = pointee != null && pointee.asStruct() != null;
                reporter.error(expression.span(),
                        "'.' needs a struct, got '" + base.display() + "'",
                        pointsAtStruct ? "it is a pointer to one; use '->'" : null);
                return Type.WORD;
            }
        }

        Type.Field field = struct.field(expression.field());
        if (field == null) {
            reporter.error(expression.span(),
                    "'" + struct.display() + "' has no member '" + expression.field() + "'",
                    memberSuggestion(struct));
            return Type.WORD;
        }
        // Reaching a member of a local struct means the struct has an address, which
        // is already true — a struct never lives in a register — but saying so keeps
        // the promotion rule honest rather than relying on that.
        markAddressTaken(expression.base());
        return field.type();
    }

    private static String memberSuggestion(Type.Struct struct) {
        if (struct.fields().isEmpty()) return null;
        StringBuilder sb = new StringBuilder("it has ");
        for (int i = 0; i < struct.fields().size(); i++) {
            if (i > 0) sb.append(i == struct.fields().size() - 1 ? " and " : ", ");
            sb.append('\'').append(struct.fields().get(i).name()).append('\'');
        }
        return sb.toString();
    }

    /**
     * {@code sizeof(type)} or {@code sizeof(expression)}, always a compile-time word.
     *
     * <p>The operand of the expression form is analysed for its type and then not
     * evaluated, as in C: {@code sizeof(p->next)} asks how big a pointer is and must
     * not follow one.
     */
    private Type analyzeSizeOf(SizeOf expression) {
        Type measured = expression.type().isPresent()
                ? resolve(expression.type().get())
                : analyzeExpression(expression.operand().get());

        if (measured.isVoid()) {
            reporter.error(expression.span(), "'void' has no size");
        }
        if (measured instanceof Type.Func) {
            reporter.error(expression.span(), "a function has no size",
                    "a pointer to one does: sizeof(word (*)(word)) is 2");
        }
        info.setSize(expression, Math.max(1, measured.size()));
        return Type.WORD;
    }

    /**
     * Refuses a struct passed or returned by value.
     *
     * <p>The calling convention pushes one word per argument and returns one word in
     * {@code A}. Widening it to carry a struct means variable-width arguments, and a
     * returned struct needs a hidden pointer to somewhere to put it — real work, for
     * a machine with 4&nbsp;KB of RAM where copying a struct across a call is the
     * expensive thing you would then be encouraged to do. A pointer costs two bytes
     * and says what was meant.
     */
    private void checkNotStructByValue(Type type, dev.madlador.diag.Span span, String what) {
        if (!type.isStruct()) return;
        reporter.error(span, what + " a struct by value: '" + type.display() + "'",
                "pass '" + type.display() + "*' instead");
    }

    /** Whether an expression denotes a place that can be assigned to or addressed. */
    private boolean isAssignablePlace(Expression expression) {
        return switch (expression) {
            case Identifier id -> {
                Symbol symbol = scope.resolve(id.name());
                yield !(symbol instanceof Symbol.Func) && !(symbol instanceof Symbol.Constant);
            }
            case Deref ignored -> true;
            case Index ignored -> true;
            case Member ignored -> true;
            default -> false;
        };
    }

    private void markAddressTaken(Expression expression) {
        // A member or a subscript reaches into the variable underneath, so that is
        // the one whose address is being taken.
        Expression target = expression;
        while (true) {
            if (target instanceof Member member) {
                if (member.throughPointer()) return;   // the pointer's target, not it
                target = member.base();
            } else if (target instanceof Index index) {
                target = index.base();
            } else {
                break;
            }
        }
        if (!(target instanceof Identifier id)) return;
        Symbol symbol = scope.resolve(id.name());
        // A parameter counts now too: argument 0 lives in a register unless something
        // wants its address, and only this notices that something does.
        if (symbol instanceof Symbol.LocalVar || symbol instanceof Symbol.ParamVar) {
            info.markAddressTaken(symbol);
        }
    }

    private Type analyzeCall(Call call) {
        for (Expression argument : call.arguments()) analyzeExpression(argument);

        if (Builtins.isBuiltin(call.callee())) return analyzeBuiltin(call);

        Symbol symbol = scope.resolve(call.callee());
        if (symbol == null) {
            reporter.error(call.span(), "undeclared function '" + call.callee() + "'");
            return Type.WORD;
        }
        if (!(symbol instanceof Symbol.Func function)) {
            Type.Func signature = symbol.type().callable();
            if (signature != null && !(symbol instanceof Symbol.Constant)) {
                // A variable holding a function pointer: `op(a, b)` calls through it.
                info.bind(call, symbol);
                checkArguments("'" + call.callee() + "'", signature.parameters(),
                        call.arguments(), call.span());
                return signature.returnType();
            }
            reporter.error(call.span(),
                    "'" + call.callee() + "' is a " + symbol.kindName() + ", not a function");
            return Type.WORD;
        }
        info.bind(call, function);
        requireDefinition(function, call.span());
        checkArguments("'" + call.callee() + "'", function.parameterTypes(),
                call.arguments(), call.span());
        return function.returnType();
    }

    /**
     * A builtin needs no declaration and has no symbol; it compiles to a single
     * instruction rather than a call.
     */
    private Type analyzeBuiltin(Call call) {
        int expected = Builtins.arityOf(call.callee());
        if (call.arguments().size() != expected) {
            reporter.error(call.span(),
                    "'" + call.callee() + "' takes " + expected + " argument(s), but "
                            + call.arguments().size() + " were given");
            return Builtins.returnTypeOf(call.callee());
        }
        for (Expression argument : call.arguments()) {
            Type type = info.typeOf(argument);
            // An array stands for its address here as it does in any other call.
            if (!type.isInteger() && !type.isPointer() && !(type instanceof Type.Arr)) {
                reporter.error(argument.span(),
                        "'" + call.callee() + "' needs integer arguments, got '"
                                + type.display() + "'");
            }
        }
        checkPortUse(call);
        checkBuiltinWrites(call);
        return Builtins.returnTypeOf(call.callee());
    }

    /** The eleven I/O registers, by port number, for use in diagnostics. */
    private static final String[] PORT_NAMES = {
            "IRQMASK", "IRQSTATUS", "IRQEOI", "TMRPRELOAD", "TMRCOUNTER",
            "KBDSTATUS", "KBDDATA", "VIDMODE", "VIDADDR", "VIDDATA", "RNDGEN"
    };

    /** Ports the machine refuses to be written to. Writing one faults the CPU. */
    private static boolean isReadOnlyPort(int port) {
        return port == 1 || port == 4 || port == 5 || port == 6 || port == 10;
    }

    /**
     * Port mistakes that the machine punishes with a dead CPU and no message.
     *
     * <p>Writing a read-only register, or touching a port above ten, raises an
     * exception the machine cannot deliver anywhere: there is no vector for it, the
     * CPU latches into fault mode, and every later step throws "CPU in FAULT mode".
     * With a literal port number that is decidable here, so it should be.
     *
     * <p>Only literals are checked, and deliberately. A computed port is legitimate —
     * a loop over the video registers, say — and a false positive on one would be far
     * worse than missing it.
     */
    private void checkPortUse(Call call) {
        boolean out = Builtins.OUT.equals(call.callee());
        boolean in = Builtins.IN.equals(call.callee());
        if ((!out && !in) || call.arguments().isEmpty()) return;

        Optional<Integer> folded = fold(call.arguments().get(0));
        if (folded.isEmpty()) return;
        int port = folded.get();
        Expression where = call.arguments().get(0);

        if (port >= PORT_NAMES.length) {
            reporter.error(where.span(),
                    "there is no port " + port + "; the machine has 0 to 10",
                    "reading or writing one faults the CPU, which cannot be recovered from");
            return;
        }
        if (out && isReadOnlyPort(port)) {
            reporter.error(where.span(),
                    "port " + port + " is " + PORT_NAMES[port] + ", which is read-only",
                    "writing it faults the CPU; use __in(" + port + ") to read it");
            return;
        }
        // A video mode above 4 is stored but does nothing, so __in(7) reports a mode
        // the card is not in — and every one of __vwrite, __vread, __vfill and
        // __waitframe branches on exactly that. __waitframe would wait forever for a
        // refresh from a display that is off.
        if (out && port == 7 && call.arguments().size() > 1) {
            fold(call.arguments().get(1)).ifPresent(mode -> {
                if (mode > 4) {
                    reporter.warning(call.arguments().get(1).span(),
                            "video mode " + mode + " does not exist; the modes are 0 to 4",
                            "the card ignores it but the register keeps it, so __in(7) "
                                    + "reports it and the __v* builtins take the wrong branch");
                }
            });
        }
    }

    private Type analyzeUnary(Unary unary) {
        Type operand = analyzeExpression(unary.operand()).promoted();

        return switch (unary.operation()) {
            case NOT -> {
                // Logical not always yields a 0/1 truth value.
                if (!operand.isInteger() && !operand.isPointer()) {
                    reporter.error(unary.span(), "'!' needs a scalar operand");
                }
                yield Type.WORD;
            }
            case NEGATE, PLUS, COMPLEMENT -> {
                if (!operand.isInteger()) {
                    reporter.error(unary.span(),
                            "'" + unary.operation().symbol() + "' needs an integer operand, got '"
                                    + operand.display() + "'");
                    yield Type.WORD;
                }
                // Negating a literal is how a negative number is written, not an
                // operation on an unsigned value. Only a computed one can surprise.
                if (unary.operation() == dev.madlador.parser.ast.UnaryOperation.NEGATE
                        && !operand.isSigned()
                        && fold(unary.operand()).isEmpty()) {
                    reporter.warning(unary.span(),
                            "negating an unsigned value wraps around",
                            "-1 as a word is 65535");
                }
                yield operand;
            }
        };
    }

    private Type analyzeBinary(Binary binary) {
        Type left = analyzeExpression(binary.left()).promoted();
        Type right = analyzeExpression(binary.right()).promoted();

        // Pointer arithmetic: p + n and p - n keep the pointer type, and the scaling
        // by element size happens during lowering.
        if (left.isPointer() || right.isPointer()) {
            return analyzePointerBinary(binary, left, right);
        }

        if (!left.isInteger() || !right.isInteger()) {
            reporter.error(binary.span(),
                    "operator '" + binary.operation().symbol() + "' needs integer operands, got '"
                            + left.display() + "' and '" + right.display() + "'");
            return Type.WORD;
        }

        // A shift has its left operand's type, as in C: the count's signedness says
        // nothing about the value being shifted, so `u >> s` is logical whatever s is,
        // and there is no mixing to warn about.
        if (binary.operation() == dev.madlador.parser.ast.BinaryOperation.SHIFT_LEFT
                || binary.operation() == dev.madlador.parser.ast.BinaryOperation.SHIFT_RIGHT) {
            return left;
        }

        boolean signed = left.isSigned() || right.isSigned();
        if (left.isSigned() != right.isSigned()
                && !isSignednessNeutral(binary.left())
                && !isSignednessNeutral(binary.right())) {
            reporter.warning(binary.span(),
                    "mixing signed and unsigned operands in '" + binary.operation().symbol() + "'",
                    "the result is treated as " + (signed ? "signed" : "unsigned"));
        }

        // A comparison produces a truth value, not a value of the operand type.
        if (binary.operation().isComparison()) return Type.WORD;
        return signed ? Type.SWORD : Type.WORD;
    }

    private Type analyzePointerBinary(Binary binary, Type left, Type right) {
        BinaryOperation op = binary.operation();

        if (op.isComparison()) return Type.WORD;

        if (op == BinaryOperation.ADD || op == BinaryOperation.SUBTRACT) {
            if (left.isPointer() && right.isInteger()) return left;
            if (left.isInteger() && right.isPointer() && op == BinaryOperation.ADD) return right;
            // p - q is the distance between them, in elements.
            if (left.isPointer() && right.isPointer() && op == BinaryOperation.SUBTRACT) {
                return Type.WORD;
            }
        }
        reporter.error(binary.span(),
                "operator '" + op.symbol() + "' cannot be applied to '"
                        + left.display() + "' and '" + right.display() + "'");
        return Type.WORD;
    }

    private Type analyzeLogical(Logical logical) {
        analyzeExpression(logical.left());
        analyzeExpression(logical.right());
        return Type.WORD;
    }

    private Type analyzeAssign(Assign assign) {
        Type valueType = analyzeExpression(assign.value());

        // The target is resolved directly rather than through analyzeExpression,
        // which would report a function name as "cannot be used as a value" and hide
        // the more useful complaint that it cannot be assigned to.
        if (assign.target() instanceof Identifier id) {
            Symbol symbol = scope.resolve(id.name());
            if (symbol == null) {
                reporter.error(id.span(), "undeclared identifier '" + id.name() + "'");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            if (symbol instanceof Symbol.Func) {
                reporter.error(assign.span(), "cannot assign to function '" + id.name() + "'");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            if (symbol instanceof Symbol.Constant) {
                reporter.error(assign.span(),
                        "cannot assign to '" + id.name() + "', which is an enum constant");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            info.bind(id, symbol);
            info.setType(id, symbol.type());
            info.bind(assign, symbol);
            checkWritable(id, assign.span());
            checkAssignableUnlessCompound(assign, symbol.type(), valueType);
            return symbol.type();
        }

        Type targetType = analyzeExpression(assign.target());
        if (isAssignablePlace(assign.target())) {
            checkWritable(assign.target(), assign.span());
            checkAssignableUnlessCompound(assign, targetType, valueType);
            return targetType;
        }
        reporter.error(assign.target().span(),
                "this is not something that can be assigned to");
        return targetType;
    }

    /**
     * {@code cond ? a : b}.
     *
     * <p>The result type is the arms' common type, worked out the way a binary
     * operator's is: promote both, and if they disagree about signedness say so rather
     * than pick one silently.
     */
    private Type analyzeConditional(Conditional conditional) {
        analyzeExpression(conditional.condition());
        Type left = analyzeExpression(conditional.then()).promoted();
        Type right = analyzeExpression(conditional.otherwise()).promoted();

        if (left.isVoid() || right.isVoid()) {
            reporter.error(conditional.span(), "a conditional expression needs a value in both arms");
            return Type.WORD;
        }
        if (left.isStruct() || right.isStruct()) {
            reporter.error(conditional.span(),
                    "a conditional expression cannot produce a struct",
                    "use a pointer, or an if statement");
            return Type.WORD;
        }
        if (left.isPointer() || right.isPointer()) return left.isPointer() ? left : right;

        if (left.isSigned() != right.isSigned()
                && !isSignednessNeutral(conditional.then())
                && !isSignednessNeutral(conditional.otherwise())) {
            reporter.warning(conditional.span(),
                    "the arms of this conditional differ in signedness",
                    "the result is treated as signed");
            return left.isSigned() ? left : right;
        }
        return left.isSigned() || right.isSigned() ? (left.isSigned() ? left : right) : left;
    }

    /**
     * {@code (type)value}.
     *
     * <p>The point of having it is to say "yes, I meant to lose those bits" — a
     * narrowing conversion warns when it is implicit and does not when it is written
     * out, which is the whole reason {@code & 255} was the idiom before this existed.
     */
    private Type analyzeCast(Cast cast) {
        Type from = analyzeExpression(cast.operand());
        Type to = resolve(cast.type());

        if (to.isVoid()) {
            // `(void)f()` discards a result deliberately. Nothing to check.
            return Type.VOID;
        }
        if (to.isStruct() || to instanceof Type.Arr) {
            reporter.error(cast.span(),
                    "cannot cast to '" + to.display() + "'",
                    "only scalars and pointers can be converted");
            return to;
        }
        if (from.isStruct() || from instanceof Type.Arr) {
            // An array decays to a pointer, which is a legitimate thing to cast.
            if (from instanceof Type.Arr) return to;
            reporter.error(cast.operand().span(),
                    "cannot cast from '" + from.display() + "'",
                    "a struct has no value to convert; take its address instead");
        }
        return to;
    }

    /**
     * {@code x++} and {@code x--}. The prefix forms never reach here: the parser
     * desugars them into an assignment, because that is exactly what they are.
     *
     * <p>The target is resolved directly rather than through
     * {@link #analyzeExpression}, for the same reason {@link #analyzeAssign} does it —
     * that path reports a function name as "cannot be used as a value" and buries the
     * more useful complaint.
     */
    private Type analyzePostfixUpdate(PostfixUpdate update) {
        if (update.target() instanceof Identifier id) {
            Symbol symbol = scope.resolve(id.name());
            if (symbol == null) {
                reporter.error(id.span(), "undeclared identifier '" + id.name() + "'");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            if (symbol instanceof Symbol.Func) {
                reporter.error(update.span(), "cannot apply '" + update.spelling()
                        + "' to function '" + id.name() + "'");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            if (symbol instanceof Symbol.Constant) {
                reporter.error(update.span(), "cannot apply '" + update.spelling()
                        + "' to '" + id.name() + "', which is an enum constant");
                info.setType(id, Type.WORD);
                return Type.WORD;
            }
            info.bind(id, symbol);
            info.setType(id, symbol.type());
            info.bind(update, symbol);
            checkWritable(id, update.span());
            checkUpdatable(symbol.type(), update);
            return symbol.type();
        }

        Type targetType = analyzeExpression(update.target());
        if (!isAssignablePlace(update.target())) {
            reporter.error(update.target().span(),
                    "'" + update.spelling() + "' needs something that can be assigned to");
            return targetType;
        }
        checkWritable(update.target(), update.span());
        checkUpdatable(targetType, update);
        return targetType;
    }

    /** Neither form means anything on an aggregate. */
    private void checkUpdatable(Type type, PostfixUpdate update) {
        if (type.isStruct() || type instanceof Type.Arr) {
            reporter.error(update.span(), "'" + update.spelling()
                    + "' cannot be applied to '" + type.display() + "'");
        }
    }

    /* ---------------- helpers ---------------- */

    /**
     * Whether an operand is a literal, and so has no signedness to disagree about.
     *
     * <p>Every integer literal in this language is a {@code word}, which means the
     * ordinary way of writing signed code — {@code n < 0}, {@code v >> 7},
     * {@code x * -1} — mixes a signed value with an unsigned one on every line. The
     * warning is then noise, and noise is worse than nothing: it trains the reader to
     * skip diagnostics that are usually right.
     *
     * <p>So a constant takes the signedness of whatever it meets, as in C. The limit
     * is what the value can honestly mean as a signed number: a literal above 32767
     * really is a different number once it is read as signed, and {@code n < 40000}
     * against an {@code sword} is worth stopping for, because it compares against
     * -25536. Writing it as {@code -25536} says the same thing and says it on purpose.
     */
    private boolean isSignednessNeutral(Expression expression) {
        if (expression instanceof Unary unary
                && unary.operation() == dev.madlador.parser.ast.UnaryOperation.NEGATE) {
            // -32768 is representable; its magnitude, 32768, is not.
            return fold(unary.operand()).filter(v -> v <= 32768).isPresent();
        }
        return fold(expression).filter(v -> v <= 32767).isPresent();
    }

    /**
     * {@code b += 1} and {@code ++b} on a byte compute in sixteen bits and store eight,
     * as C does, without a truncation warning: the narrowing is what the operator
     * means, not an accident of a wider right-hand side. Everything else a plain
     * assignment checks — pointers, structs, functions — still applies.
     */
    private void checkAssignableUnlessCompound(Assign assign, Type target, Type value) {
        if (assign.compound() && target.isInteger() && value.isInteger()) return;
        checkAssignable(target, value, assign.value());
    }

    private void checkAssignable(Type target, Type value, Expression where) {
        if (target.isVoid() || value.isVoid()) {
            reporter.error(where.span(), "'void' has no value");
            return;
        }
        if (target instanceof Type.Arr) {
            reporter.error(where.span(), "cannot assign to an array",
                    "assign its elements, or give it an initializer where it is declared");
            return;
        }
        // A struct is assignable only from the same struct, and identity is by
        // declaration: two structs with matching members are still two types.
        if (target.isStruct() || value.isStruct()) {
            if (target != value) {
                reporter.error(where.span(),
                        "cannot assign '" + value.display() + "' to '" + target.display() + "'");
            }
            return;
        }
        if (target.isInteger() && value.isInteger()) {
            // Compare the declared widths, not the promoted ones: byte-to-byte is
            // not a truncation even though both sides promote to a word for
            // arithmetic.
            if (target.size() >= value.size()) return;

            // A literal that already fits is not a truncation, so `byte b = 7;`
            // should be silent. Only warn when the value could actually be lost.
            java.util.Optional<Integer> folded = fold(where);
            int max = target.size() == 1 ? 0xFF : 0xFFFF;
            if (folded.isPresent()) {
                int constant = folded.get();
                // A negative constant is a large 16-bit pattern, and for a signed byte
                // the patterns 0xFF80..0xFFFF are exactly -128..-1, which fit. Without
                // this, `sbyte x = -1;` warned that 65535 does not fit.
                boolean negativeThatFits = target.isSigned() && constant >= 0x10000 - (max + 1) / 2;
                if (constant > max && !negativeThatFits) {
                    reporter.warning(where.span(),
                            "the value " + constant + " does not fit in '" + target.display()
                                    + "' and becomes " + (constant & max));
                }
                return;
            }
            // Nor is it a truncation when the arithmetic cannot produce a value that
            // large. `screen[i] = '0' + n % 10` is the everyday case: promotion made
            // it a word, but 57 is as big as it gets.
            if (ValueRange.fitsIn(where, max)) return;

            reporter.warning(where.span(),
                    "assigning '" + value.display() + "' to '" + target.display()
                            + "' truncates to " + (target.size() * 8) + " bits");
            return;
        }
        if (target.callable() != null) {
            Type.Func wanted = target.callable();
            Type.Func given = value.callable();
            if (given != null) {
                if (!given.equals(wanted)) {
                    reporter.error(where.span(),
                            "cannot assign '" + value.display() + "' to '" + target.display() + "'",
                            "the signatures differ, and a call through it would be wrong");
                }
                return;
            }
            boolean nullPointer = value.isInteger() && fold(where).filter(v -> v == 0).isPresent();
            if (!nullPointer) {
                reporter.error(where.span(),
                        "cannot assign '" + value.display() + "' to '" + target.display() + "'",
                        "a function pointer takes a function, or 0; a cast says anything else is meant");
            }
            return;
        }
        if (target instanceof Type.Ptr to && !to.toConst() && value.pointsToConst()) {
            reporter.warning(where.span(),
                    "assigning '" + value.display() + "' to '" + target.display() + "' drops 'const'",
                    "what it points at could then be written; a cast says that is meant");
            return;
        }
        if (target.isPointer() || value.isPointer()) return;

        reporter.error(where.span(),
                "cannot assign '" + value.display() + "' to '" + target.display() + "'");
    }

    /* ---------------- constants ---------------- */

    /** Folds an expression this pass has already analysed, names and all. */
    private Optional<Integer> fold(Expression expression) {
        return ConstantFolder.fold(expression, info);
    }

    /** A constant as a variable of {@code type} holds it: a byte keeps eight bits. */
    private static int stored(Type type, int value) {
        if (!type.isByteWidth()) return value & 0xFFFF;
        return type.isSigned() ? ((byte) value) & 0xFFFF : value & 0xFF;
    }

    /**
     * Declares every enumerator in the current scope, each one more than the last
     * unless it says otherwise. A value may use the enumerators before it, which is
     * why they are declared one at a time rather than all at once.
     */
    private void declareEnum(EnumDeclaration declaration) {
        if (declaration.name() != null && !enumTags.add(declaration.name())) {
            reporter.error(declaration.span(),
                    "'enum " + declaration.name() + "' is already declared");
        }
        int next = 0;
        for (Enumerator member : declaration.members()) {
            int value = next;
            if (member.value().isPresent()) {
                Expression written = member.value().get();
                analyzeExpression(written);
                Optional<Integer> folded = fold(written);
                if (folded.isEmpty()) {
                    reporter.error(written.span(), "an enum value must be a constant expression");
                } else {
                    value = folded.get();
                }
            }
            Symbol.Constant symbol = new Symbol.Constant(member.name(), value, member.span());
            if (!scope.declare(symbol)) {
                reporter.error(member.span(), "'" + member.name() + "' is already declared");
            }
            next = (value + 1) & 0xFFFF;
        }
    }

    /**
     * What an array length written as an expression comes to — an enum constant, a
     * {@code const}, a {@code sizeof}, arithmetic on them. Worked out once per
     * expression, because the same declared type is resolved more than once.
     */
    private int computedLength(Expression expression) {
        Integer known = computedLengths.get(expression);
        if (known != null) return known;
        analyzeExpression(expression);
        Optional<Integer> folded = fold(expression);
        int length = 1;
        if (folded.isEmpty()) {
            reporter.error(expression.span(), "an array length must be a constant expression",
                    "a const with a constant initializer, an enum constant or sizeof will do");
        } else if (folded.get() == 0) {
            reporter.error(expression.span(), "an array must have at least one element");
        } else {
            length = folded.get();
        }
        computedLengths.put(expression, length);
        return length;
    }

    /* ---------------- const ---------------- */

    /**
     * Whether a place may not be written: a const variable, or anything reached
     * through a pointer to const, or inside a const array or struct.
     */
    private boolean isReadOnly(Expression place) {
        return switch (place) {
            case Identifier id -> {
                Symbol symbol = info.symbolOf(id);
                yield symbol instanceof Symbol.Constant || readOnly.contains(symbol);
            }
            case Deref d -> recordedType(d.operand()).pointsToConst();
            case Index x -> {
                Type base = recordedType(x.base());
                yield base.pointsToConst() || (base instanceof Type.Arr && isReadOnly(x.base()));
            }
            case Member m -> m.throughPointer()
                    ? recordedType(m.base()).pointsToConst()
                    : isReadOnly(m.base());
            default -> false;
        };
    }

    private Type recordedType(Expression expression) {
        Type type = info.typeIfRecorded(expression);
        return type == null ? Type.WORD : type;
    }

    /** Refuses a write to something const, and says what made it so. */
    private void checkWritable(Expression target, Span where) {
        if (!isReadOnly(target)) return;
        if (target instanceof Identifier id) {
            reporter.error(where, "'" + id.name() + "' is const, so it cannot be assigned to");
        } else {
            reporter.error(where, "this is const, so it cannot be assigned to",
                    "it is reached through a pointer to const, or inside something const");
        }
    }

    /**
     * The builtins that store through an argument warn when it points to const, the
     * way passing one to a function whose parameter is not const does.
     */
    private void checkBuiltinWrites(Call call) {
        int written = switch (call.callee()) {
            case Builtins.MEMCPY, Builtins.MEMSET, Builtins.STRCPY -> 0;
            case Builtins.VREAD -> 1;
            default -> -1;
        };
        if (written < 0 || written >= call.arguments().size()) return;
        Expression target = call.arguments().get(written);
        if (recordedType(target).pointsToConst()) {
            reporter.warning(target.span(),
                    "'" + call.callee() + "' writes through this, but it points to const");
        }
    }

    /* ---------------- initializers ---------------- */

    /**
     * A declaration's type, with an array written {@code []} counted from its
     * initializer as C does: {@code word a[] = {1, 2, 3}} is three words, and
     * {@code byte s[] = "hi"} three bytes.
     */
    private Type declaredType(TypeRef ref, Optional<Expression> scalar,
                              Optional<InitializerList> list, dev.madlador.diag.Span where) {
        if (!ref.hasUnsizedDimension()) return resolve(ref);
        Type element = resolve(ref.withoutOuterDimension());
        int count;
        if (list.isPresent()) {
            count = new Layout(false).count(element, list.get());
        } else if (scalar.isPresent() && scalar.get() instanceof StringLiteral string
                && element.isByteWidth()) {
            count = string.value().length() + 1;
        } else {
            reporter.error(where, "an array with no length needs an initializer to count",
                    "write the length, or give it '= { ... }'");
            count = 1;
        }
        if (count == 0) {
            reporter.error(where, "an array must have at least one element");
            count = 1;
        }
        return new Type.Arr(element, count, ref.isConstAt(ref.pointerDepth()));
    }

    /**
     * Works out where an aggregate's initializer puts each value and records it for
     * lowering. False when there is nothing aggregate about it — a scalar with a scalar
     * initializer — which the caller handles as it always did.
     */
    private boolean layOutInitializer(Node owner, Type type, Optional<Expression> scalar,
                                      Optional<InitializerList> list) {
        Layout layout = new Layout(true);
        if (list.isPresent()) {
            layout.braced(type, list.get(), 0);
        } else if (scalar.isPresent() && type instanceof Type.Arr array) {
            if (isByteArray(array) && scalar.get() instanceof StringLiteral string) {
                layout.string(array, string, 0);
            } else {
                reporter.error(scalar.get().span(), "an array is initialized with a list",
                        "write '= { ... }', or a string for an array of bytes");
            }
        } else {
            return false;
        }
        info.setInitialization(owner, layout.entries);
        return true;
    }

    /**
     * Refuses anything in a global's or a static's initializer that is not known before
     * the program runs: it becomes bytes in the image, with nothing to compute it.
     */
    private void requireConstantEntries(List<SemanticInfo.InitEntry> entries, String what) {
        for (SemanticInfo.InitEntry entry : entries) {
            if (entry.isLiteral() || fold(entry.value()).isPresent()) continue;
            if (isAddressConstant(entry.value())) {
                if (entry.type().size() != 2) {
                    reporter.error(entry.value().span(),
                            "an address takes two bytes, and this is '" + entry.type().display() + "'");
                }
                continue;
            }
            reporter.error(entry.value().span(), what + " must be a constant expression",
                    "a number, or the address of a string, a global or a function");
        }
    }

    /**
     * Whether a value is the address of something whose place in the image is fixed —
     * a string, a global, a function — which a global's data can hold as a label. The
     * assembler has no label arithmetic, so the address of an element or a member,
     * which would need {@code label+n}, is not one.
     */
    private boolean isAddressConstant(Expression value) {
        return switch (value) {
            case StringLiteral ignored -> true;
            case Cast cast -> isAddressConstant(cast.operand());
            case AddressOf address -> address.operand() instanceof Identifier id
                    && (info.symbolOf(id) instanceof Symbol.GlobalVar
                        || info.symbolOf(id) instanceof Symbol.Func);
            case Identifier id -> (info.symbolOf(id) instanceof Symbol.GlobalVar global
                    && global.type() instanceof Type.Arr)
                    || info.symbolOf(id) instanceof Symbol.Func;
            default -> false;
        };
    }

    private static boolean isAggregate(Type type) {
        return type instanceof Type.Arr || type.isStruct();
    }

    private static boolean isByteArray(Type type) {
        return type instanceof Type.Arr array && array.element().isByteWidth();
    }

    /** A position in one braced list, shared by everything that list fills. */
    private static final class Cursor {
        private final List<Initializer> items;
        private int next;

        Cursor(List<Initializer> items) {
            this.items = items;
        }

        boolean hasNext() {
            return next < items.size();
        }

        Initializer peek() {
            return items.get(next);
        }

        Initializer take() {
            return items.get(next++);
        }

        int position() {
            return next;
        }
    }

    /**
     * Where each value of an initializer lands, by C's rules: nested braces for nested
     * aggregates, or none — a flat list fills them in order, which is C's brace
     * elision — a string for an array of bytes, the first member of a union, and zero
     * for everything left unsaid.
     *
     * <p>Run once without analysis to count the elements of a {@code []} array, whose
     * type is not known until the count is, and once for real.
     */
    private final class Layout {
        private final boolean analyze;
        private final List<SemanticInfo.InitEntry> entries = new ArrayList<>();

        Layout(boolean analyze) {
            this.analyze = analyze;
        }

        /** How many elements of {@code element} a list for an unsized array fills. */
        int count(Type element, InitializerList list) {
            Cursor cursor = new Cursor(list.items());
            int elements = 0;
            while (cursor.hasNext()) {
                int before = cursor.position();
                one(element, cursor, 0);
                if (cursor.position() == before) cursor.take();
                elements++;
            }
            return elements;
        }

        /** {@code list} as the whole initializer of something of {@code type}. */
        void braced(Type type, InitializerList list, int offset) {
            if (!isAggregate(type)) {
                // `word x = {5}` is legal C: one scalar, in braces.
                if (list.items().size() != 1
                        || !(list.items().get(0) instanceof Initializer.Value value)) {
                    error(list.span(), "'" + type.display() + "' takes one value, not a list");
                    return;
                }
                scalar(type, value.expression(), offset);
                return;
            }
            Cursor cursor = new Cursor(list.items());
            fill(type, cursor, offset);
            if (cursor.hasNext()) {
                error(cursor.peek().span(), "too many values for '" + type.display() + "'");
            }
        }

        /** Fills an aggregate's elements or members for as long as there are values. */
        private void fill(Type type, Cursor cursor, int offset) {
            if (type instanceof Type.Arr array) {
                int size = array.element().size();
                for (int i = 0; i < array.length() && cursor.hasNext(); i++) {
                    one(array.element(), cursor, offset + i * size);
                }
            } else if (type instanceof Type.Struct struct) {
                List<Type.Field> fields = struct.fields();
                int members = struct.isUnion() ? Math.min(1, fields.size()) : fields.size();
                for (int i = 0; i < members && cursor.hasNext(); i++) {
                    one(fields.get(i).type(), cursor, offset + fields.get(i).offset());
                }
            }
        }

        /** One element or member: braced, a string, elided, or a single value. */
        private void one(Type type, Cursor cursor, int offset) {
            Initializer item = cursor.peek();
            if (item instanceof InitializerList nested) {
                cursor.take();
                braced(type, nested, offset);
                return;
            }
            Expression value = ((Initializer.Value) item).expression();
            if (isByteArray(type) && value instanceof StringLiteral string) {
                cursor.take();
                string((Type.Arr) type, string, offset);
                return;
            }
            if (isAggregate(type)) {
                fill(type, cursor, offset);
                return;
            }
            cursor.take();
            scalar(type, value, offset);
        }

        private void scalar(Type type, Expression value, int offset) {
            if (!analyze) return;
            Type actual = analyzeExpression(value);
            checkAssignable(type, actual, value);
            entries.add(new SemanticInfo.InitEntry(offset, type, value, 0));
        }

        /**
         * A string's bytes, terminator included when it fits. C lets the terminator
         * fall off an array exactly as long as the text, and so does this.
         */
        void string(Type.Arr array, StringLiteral string, int offset) {
            if (!analyze) return;
            String text = string.value();
            if (text.length() > array.length()) {
                error(string.span(), "the string is " + (text.length() + 1)
                        + " bytes with its terminator, and '" + array.display() + "' holds "
                        + array.length());
            }
            int stored = Math.min(text.length() + 1, array.length());
            for (int i = 0; i < stored; i++) {
                int value = i < text.length() ? text.charAt(i) & 0xFF : 0;
                entries.add(new SemanticInfo.InitEntry(offset + i, array.element(), null, value));
            }
        }

        private void error(dev.madlador.diag.Span span, String message) {
            if (analyze) reporter.error(span, message);
        }
    }

    /* ---------------- function pointers ---------------- */

    private static Type.Func functionType(Symbol.Func function) {
        return new Type.Func(function.returnType(), function.parameterTypes());
    }

    /**
     * A call through an expression: {@code (*fp)(x)}, {@code table[i](x)},
     * {@code button->press()}. Checked against the signature the pointer carries.
     */
    private Type analyzeIndirectCall(IndirectCall call) {
        Type callee = analyzeExpression(call.callee());
        for (Expression argument : call.arguments()) analyzeExpression(argument);
        Type.Func signature = callee.callable();
        if (signature == null) {
            reporter.error(call.callee().span(),
                    "this is '" + callee.display() + "', not a function or a pointer to one");
            return Type.WORD;
        }
        checkArguments("the function it points to", signature.parameters(),
                call.arguments(), call.span());
        return signature.returnType();
    }

    private void checkArguments(String callee, List<Type> expected, List<Expression> arguments,
                                dev.madlador.diag.Span where) {
        if (expected.size() != arguments.size()) {
            reporter.error(where, callee + " takes " + expected.size() + " argument(s), but "
                    + arguments.size() + " were given");
            return;
        }
        for (int i = 0; i < expected.size(); i++) {
            Expression argument = arguments.get(i);
            checkAssignable(expected.get(i), info.typeOf(argument), argument);
        }
    }

    /* ---------------- goto ---------------- */

    /**
     * Every goto against its label, once the whole function has been seen — a jump
     * may go forward, to a label not yet reached.
     *
     * <p>Within one function, and out of blocks or within one, never into one: the
     * label's block has to enclose the goto. And never forward past a declaration in
     * the label's block, which would reach code using a variable nothing set up. C
     * allows both of those and leaves the result to chance; this does not.
     */
    private void checkJumps() {
        Set<String> used = new HashSet<>();
        for (JumpSite jump : gotos) {
            JumpSite label = labels.get(jump.label());
            if (label == null) {
                reporter.error(jump.span(), "there is no label '" + jump.label() + "' in this function");
                continue;
            }
            used.add(label.label());
            checkJump(jump, label);
        }
        for (JumpSite label : labels.values()) {
            if (!used.contains(label.label())) {
                reporter.warning(label.span(), "label '" + label.label() + "' is never used");
            }
        }
    }

    private void checkJump(JumpSite from, JumpSite to) {
        int depth = to.blocks().size();
        boolean encloses = from.blocks().size() >= depth
                && from.blocks().get(depth - 1) == to.blocks().get(depth - 1);
        for (int i = 0; encloses && i < depth - 1; i++) {
            encloses = from.blocks().get(i) == to.blocks().get(i)
                    && from.indices().get(i).equals(to.indices().get(i));
        }
        if (!encloses) {
            reporter.error(from.span(), "'goto " + to.label() + "' jumps into a block",
                    "a goto may leave blocks, or move within one, but not enter one");
            return;
        }
        int start = from.indices().get(depth - 1);
        int end = to.indices().get(depth - 1);
        List<BlockItem> items = itemsOf(to.blocks().get(depth - 1));
        for (int i = start + 1; i < end; i++) {
            if (items.get(i) instanceof Declaration declaration && !declaration.isStatic()) {
                reporter.error(from.span(), "'goto " + to.label() + "' jumps over the declaration of '"
                                + declaration.name() + "'",
                        "move the declaration above the goto, or the label above the declaration");
                return;
            }
        }
    }

    private static List<BlockItem> itemsOf(Object owner) {
        return owner instanceof Block block ? block.items() : ((SwitchCase) owner).body();
    }
}
