package dev.madlador.ir;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.parser.ast.Assign;
import dev.madlador.parser.ast.Binary;
import dev.madlador.parser.ast.BinaryOperation;
import dev.madlador.parser.ast.Block;
import dev.madlador.parser.ast.BlockItem;
import dev.madlador.parser.ast.Constant;
import dev.madlador.parser.ast.Comma;
import dev.madlador.parser.ast.DoWhile;
import dev.madlador.parser.ast.EnumDeclaration;
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
import dev.madlador.parser.ast.FunctionDefinition;
import dev.madlador.parser.ast.Goto;
import dev.madlador.parser.ast.Labeled;
import dev.madlador.parser.ast.IndirectCall;
import dev.madlador.parser.ast.Identifier;
import dev.madlador.parser.ast.Logical;
import dev.madlador.parser.ast.LogicalOperation;
import dev.madlador.parser.ast.Member;
import dev.madlador.parser.ast.PostfixUpdate;
import dev.madlador.parser.ast.Program;
import dev.madlador.parser.ast.SizeOf;
import dev.madlador.parser.ast.StructDeclaration;
import dev.madlador.parser.ast.Return;
import dev.madlador.parser.ast.Statement;
import dev.madlador.parser.ast.Unary;
import dev.madlador.codegen.Runtime;
import dev.madlador.sema.Builtins;
import dev.madlador.sema.ConstantFolder;
import dev.madlador.sema.Mangler;

import java.util.IdentityHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import dev.madlador.sema.SemanticInfo;
import dev.madlador.sema.Symbol;
import dev.madlador.sema.Type;

/**
 * Lowers the syntax tree to {@link Ir}.
 *
 * <p>Variables become frame slots and expression results become virtual registers.
 * Nothing here knows about the machine's registers or its odd frame offsets.
 *
 * <p>Conditions are lowered through {@link #lowerCondition}, which emits a
 * {@link Ir.Cbr} straight from a comparison rather than materialising a 0/1 and then
 * testing it. On this machine that is the difference between two instructions and
 * about seven, and it is why {@code &&} and {@code ||} cost nothing beyond the
 * branches they genuinely need.
 */
public final class Lowering {

    private final SemanticInfo info;
    private final DiagnosticReporter reporter;
    private final Mangler mangler;

    private Ir.Module module;
    private Ir.Function function;
    private Ir.BasicBlock block;
    private int nextVreg;

    /** The compiler-owned global holding the installed interrupt handler. */
    public static final String ISR_VECTOR_LABEL = "g_isr_vector";

    /** Local slots eligible to become virtual registers; see PromoteLocals. */
    private final java.util.Set<Integer> promotable = new java.util.LinkedHashSet<>();

    /** String literal contents to the label holding them, so duplicates share. */
    private final Map<String, String> strings = new LinkedHashMap<>();

    /** Where `break` and `continue` jump to, innermost loop last. */
    private final Deque<LoopTargets> loops = new ArrayDeque<>();

    private record LoopTargets(Ir.BasicBlock continueTarget, Ir.BasicBlock breakTarget) {
    }

    public Lowering(SemanticInfo info, DiagnosticReporter reporter, Mangler mangler) {
        this.info = info;
        this.reporter = reporter;
        this.mangler = mangler;
    }

    public Ir.Module lower(Program program) {
        module = new Ir.Module();

        for (TopLevel item : program.items()) {
            switch (item) {
                case StructDeclaration ignored -> { }   // a layout; it emits nothing
                case EnumDeclaration ignored -> { }     // names for numbers; nor does this
                case FunctionDeclaration ignored -> { }  // a signature; the definition emits
                case FunctionDefinition f -> {
                    Ir.Function lowered = lowerFunction(f);
                    module.functions().add(lowered);
                    if (f.name().equals("main")) {
                        module.setEntryFunctionLabel(lowered.label());
                    }
                }
                case GlobalDeclaration g -> {
                    Symbol.GlobalVar symbol = (Symbol.GlobalVar) info.symbolOf(g);
                    if (symbol == null) break;
                    module.globals().add(globalData(symbol, info.initializationOf(g)));
                }
            }
        }

        strings.forEach((value, label) ->
                module.strings().add(new Ir.StringData(label, value)));

        if (module.usesInterrupts()) {
            // Zero means "nothing installed", which the trampoline checks before
            // jumping — otherwise an interrupt before __setisr would land at 0.
            module.globals().add(new Ir.Global(ISR_VECTOR_LABEL, 2, 0));
        }
        return module;
    }

    private Ir.Function lowerFunction(FunctionDefinition definition) {
        Symbol.Func symbol = (Symbol.Func) info.symbolOf(definition);
        function = new Ir.Function(definition.name(), symbol.label(),
                definition.parameters().size());
        nextVreg = 0;

        block = new Ir.BasicBlock(symbol.label());
        function.blocks().add(block);
        promotable.clear();
        labelBlocks.clear();

        receiveRegisterArgument(definition);
        lowerBlock(definition.body());

        // Falling off the end returns zero, which also gives `main` a defined result.
        if (!block.isTerminated()) {
            Type returnType = Type.of(definition.returnType());
            emit(new Ir.Ret(returnType.isVoid() ? null : new Ir.Imm(0)));
        }

        function.setSlotCount(info.frameOf(definition).slotCount());
        function.setVregCount(nextVreg);
        function.promotableSlots().addAll(promotable);
        return function;
    }

    /**
     * Takes delivery of the argument the caller left in a register.
     *
     * <p>Written to the parameter's slot, first thing, so that everything downstream —
     * reads, assignments, taking its address — treats it as an ordinary variable and
     * needs to know nothing about the calling convention. The store is usually not
     * emitted: the slot is marked promotable, so unless something takes the
     * parameter's address the optimizer lifts it back into a register and the
     * selector finds it already in {@code B}.
     */
    private void receiveRegisterArgument(FunctionDefinition definition) {
        if (definition.parameters().isEmpty()) return;
        Symbol symbol = info.symbolOf(definition.parameters().get(0));
        if (!(symbol instanceof Symbol.ParamVar param) || !param.inRegister()) return;

        Ir.VReg arrived = freshVreg();
        emit(new Ir.ArgIn(arrived, 0));
        emit(new Ir.Store(new Ir.Addr.Local(param.slot()), arrived, Ir.Width.WORD));
        if (isPromotable(param)) promotable.add(param.slot());
    }

    /* ---------------- block management ---------------- */

    /** The block each label of the function being lowered starts, made on first mention. */
    private final Map<String, Ir.BasicBlock> labelBlocks = new HashMap<>();

    private Ir.BasicBlock labelBlock(String label) {
        return labelBlocks.computeIfAbsent(label, l -> newBlock("label_" + l));
    }

    private Ir.BasicBlock newBlock(String hint) {
        return new Ir.BasicBlock(mangler.internal(hint));
    }

    /** Makes {@code target} the block being written to, appending it to the function. */
    private void startBlock(Ir.BasicBlock target) {
        function.blocks().add(target);
        block = target;
    }

    private void branchTo(Ir.BasicBlock target) {
        if (!block.isTerminated()) emit(new Ir.Br(target.label()));
    }

    /**
     * Appends to whichever block is current <em>now</em>.
     *
     * <p>Always emit through this rather than writing {@code block.add(...)} with a
     * nested lowering call in the argument. Java evaluates the receiver before the
     * argument, so {@code block.add(new Ir.Ret(lowerExpression(e)))} appends to the
     * block that was current before {@code e} was lowered — which is the wrong one
     * as soon as {@code e} contains a short-circuit operator and opens new blocks.
     * Reading the field inside this method closes that trap for good.
     */
    private void emit(Ir.Instr instruction) {
        block.add(instruction);
    }

    /* ---------------- statements ---------------- */

    private void lowerBlock(Block b) {
        lowerItems(b.items());
    }

    /**
     * Everything after a return or a goto in the same block is unreachable — unless a
     * label further on makes it a target, and then it has to be there. So lowering
     * carries on in a block of its own, which nothing falls into.
     */
    private void lowerItems(List<BlockItem> items) {
        for (int i = 0; i < items.size(); i++) {
            if (block.isTerminated()) {
                if (!containsLabel(items.subList(i, items.size()))) break;
                startBlock(newBlock("unreached"));
            }
            switch (items.get(i)) {
                case Declaration d -> lowerDeclaration(d);
                case EnumDeclaration ignored -> { }   // names for numbers; no storage
                case Statement s -> lowerStatement(s);
            }
        }
    }

    private static boolean containsLabel(List<BlockItem> items) {
        for (BlockItem item : items) {
            if (item instanceof Statement statement && containsLabel(statement)) return true;
        }
        return false;
    }

    private static boolean containsLabel(Statement statement) {
        return switch (statement) {
            case Labeled ignored -> true;
            case Block b -> containsLabel(b.items());
            case If s -> containsLabel(s.thenBranch())
                    || s.elseBranch().map(Lowering::containsLabel).orElse(false);
            case While s -> containsLabel(s.body());
            case DoWhile s -> containsLabel(s.body());
            case For s -> containsLabel(s.body());
            case Switch sw -> sw.cases().stream().anyMatch(arm -> containsLabel(arm.body()));
            default -> false;
        };
    }

    private void lowerDeclaration(Declaration declaration) {
        Symbol symbol = info.symbolOf(declaration);
        if (symbol == null) return;
        List<SemanticInfo.InitEntry> entries = info.initializationOf(declaration);
        // A static local is a global only its block can name. Its storage goes out with
        // the other globals, initialised in the image, and reaching the line does
        // nothing at all — which is the whole difference from a local.
        if (declaration.isStatic() && symbol instanceof Symbol.GlobalVar global) {
            module.globals().add(globalData(global, entries));
            return;
        }
        if (entries != null) {
            if (symbol.type() instanceof Type.Arr || symbol.type().isStruct()) {
                storeInitializer(symbol, entries);
            } else if (entries.size() == 1 && !entries.get(0).isLiteral()) {
                emitStore(symbol, lowerExpression(entries.get(0).value()));   // `word x = {5}`
            }
            return;
        }
        if (declaration.initializer().isEmpty()) return;
        emitStore(symbol, lowerExpression(declaration.initializer().get()));
    }

    /**
     * A global's starting bytes, from its initializer's layout: each value folded and
     * written big-endian at its offset, or — for the address of a string, a global or a
     * function — left for the assembler as a label. Everything unsaid is zero.
     */
    private Ir.Global globalData(Symbol.GlobalVar symbol, List<SemanticInfo.InitEntry> entries) {
        int size = symbol.type().size();
        byte[] image = new byte[Math.max(1, size)];
        Map<Integer, String> addresses = new HashMap<>();
        if (entries != null) {
            for (SemanticInfo.InitEntry entry : entries) {
                int value;
                if (entry.isLiteral()) {
                    value = entry.literal();
                } else {
                    Optional<Integer> folded = ConstantFolder.fold(entry.value(), info);
                    if (folded.isEmpty()) {
                        String label = addressLabel(entry.value());
                        if (label != null) addresses.put(entry.offset(), label);
                        continue;
                    }
                    value = folded.get();
                }
                if (entry.type().size() == 1) {
                    image[entry.offset()] = (byte) value;
                } else {
                    image[entry.offset()] = (byte) (value >> 8);
                    image[entry.offset() + 1] = (byte) value;
                }
            }
        }
        return new Ir.Global(symbol.label(), size, image, addresses);
    }

    /** The label an address constant names; analysis has already said it is one. */
    private String addressLabel(Expression value) {
        return switch (value) {
            case StringLiteral string -> internString(string.value());
            case Cast cast -> addressLabel(cast.operand());
            case AddressOf address -> address.operand() instanceof Identifier id
                    ? labelOf(info.symbolOf(id)) : null;
            case Identifier id -> labelOf(info.symbolOf(id));
            default -> null;
        };
    }

    private static String labelOf(Symbol symbol) {
        if (symbol instanceof Symbol.GlobalVar global) return global.label();
        if (symbol instanceof Symbol.Func function) return function.label();
        return null;
    }

    /**
     * Past this many bytes of zeros, a local aggregate is cleared with one call to
     * {@code __memset} before its values are stored, rather than a store per word. The
     * stores are faster — one instruction a word against five a byte — but four bytes
     * of code each, and on this machine the code is what runs out first.
     */
    private static final int CLEAR_BY_CALL = 32;

    /**
     * A local aggregate's initializer: each value stored at its offset, and zero
     * everywhere the source said nothing, as C requires.
     */
    private void storeInitializer(Symbol symbol, List<SemanticInfo.InitEntry> entries) {
        int size = symbol.type().size();
        boolean[] covered = new boolean[size];
        for (SemanticInfo.InitEntry entry : entries) {
            for (int i = 0; i < entry.type().size(); i++) covered[entry.offset() + i] = true;
        }
        int zeros = 0;
        for (boolean c : covered) if (!c) zeros++;

        // A frame holds at most 64 slots, 128 bytes, so every offset into a local fits a
        // signed-byte displacement from its base and none needs a register moved on.
        Ir.VReg base = freshVreg();
        emit(new Ir.AddrOf(base, addressOf(symbol)));
        boolean cleared = zeros > CLEAR_BY_CALL;
        if (cleared) {
            module.runtimeHelpers().add(Runtime.MEMSET);
            emit(new Ir.Call(null, Runtime.MEMSET, List.of(base, new Ir.Imm(0), new Ir.Imm(size))));
        }
        for (SemanticInfo.InitEntry entry : entries) {
            Ir.Value value = entry.isLiteral()
                    ? new Ir.Imm(entry.literal())
                    : lowerExpression(entry.value());
            if (cleared && value instanceof Ir.Imm imm && imm.value() == 0) continue;
            emit(new Ir.Store(new Ir.Addr.Mem(base, entry.offset()), value, widthOf(entry.type())));
        }
        if (cleared) return;
        for (int offset = 0; offset < size; ) {
            if (covered[offset]) {
                offset++;
                continue;
            }
            boolean pair = offset + 1 < size && !covered[offset + 1];
            emit(new Ir.Store(new Ir.Addr.Mem(base, offset), new Ir.Imm(0),
                    pair ? Ir.Width.WORD : Ir.Width.BYTE));
            offset += pair ? 2 : 1;
        }
    }

    private void lowerStatement(Statement statement) {
        switch (statement) {
            case Block b -> lowerBlock(b);
            case Return r -> lowerReturn(r);
            case ExpressionStatement e -> lowerForEffect(e.expression());
            case If s -> lowerIf(s);
            case While s -> lowerWhile(s);
            case DoWhile s -> lowerDoWhile(s);
            case For s -> lowerFor(s);
            case Break s -> lowerBreak(s);
            case Continue s -> lowerContinue(s);
            case Empty ignored -> { }
            case Switch sw -> lowerSwitch(sw);
            case Goto g -> branchTo(labelBlock(g.label()));
            case Labeled l -> {
                Ir.BasicBlock target = labelBlock(l.label());
                branchTo(target);
                startBlock(target);
                lowerStatement(l.body());
            }
        }
    }

    /** What a case label came to; analysis folded it, names and all. */
    private int caseValue(SwitchCase arm) {
        return info.caseValueOf(arm);
    }

    /** Fewest cases worth building a table for. */
    private static final int MIN_TABLE_CASES = 4;

    /** Largest table worth emitting, in entries — each costs two bytes of image. */
    private static final int MAX_TABLE_SPAN = 256;

    /**
     * A switch.
     *
     * <p>Arms are lowered into consecutive blocks that fall into one another, which
     * is C's fall-through; {@code break} jumps to the end. Dispatch is either a chain
     * of comparisons or a jump table, chosen on how dense the labels are — a table
     * costs two bytes per entry across the whole span, so it only pays when the
     * labels actually fill it.
     */
    private void lowerSwitch(Switch statement) {
        Ir.Value subject = lowerExpression(statement.subject());

        List<SwitchCase> arms = statement.cases();
        Ir.BasicBlock done = newBlock("switch_end");

        List<Ir.BasicBlock> armBlocks = new ArrayList<>(arms.size());
        for (int i = 0; i < arms.size(); i++) armBlocks.add(newBlock("case"));

        Ir.BasicBlock defaultBlock = done;
        for (int i = 0; i < arms.size(); i++) {
            if (arms.get(i).isDefault()) defaultBlock = armBlocks.get(i);
        }

        List<SwitchCase> valued = arms.stream().filter(a -> !a.isDefault()).toList();
        if (useJumpTable(valued)) {
            emitTableDispatch(subject, valued, arms, armBlocks, defaultBlock);
        } else {
            emitChainDispatch(subject, arms, armBlocks, defaultBlock);
        }

        // The arms themselves, each falling into the next.
        Ir.BasicBlock enclosingContinue = loops.isEmpty() ? null : loops.peek().continueTarget();
        for (int i = 0; i < arms.size(); i++) {
            startBlock(armBlocks.get(i));
            loops.push(new LoopTargets(enclosingContinue, done));
            lowerItems(arms.get(i).body());
            loops.pop();
            branchTo(i + 1 < arms.size() ? armBlocks.get(i + 1) : done);
        }

        startBlock(done);
    }

    private boolean useJumpTable(List<SwitchCase> valued) {
        if (valued.size() < MIN_TABLE_CASES) return false;
        int lowest = valued.stream().mapToInt(this::caseValue).min().orElse(0);
        int highest = valued.stream().mapToInt(this::caseValue).max().orElse(0);
        long span = (long) highest - lowest + 1;
        // Worth it only when the labels fill at least half the span they cover.
        return span <= MAX_TABLE_SPAN && span <= 2L * valued.size();
    }

    private void emitTableDispatch(Ir.Value subject, List<SwitchCase> valued,
                                   List<SwitchCase> arms, List<Ir.BasicBlock> armBlocks,
                                   Ir.BasicBlock defaultBlock) {
        int lowest = valued.stream().mapToInt(this::caseValue).min().orElse(0);
        int highest = valued.stream().mapToInt(this::caseValue).max().orElse(0);
        int span = highest - lowest + 1;

        Ir.Value biased = subject;
        if (lowest != 0) {
            Ir.VReg shifted = freshVreg();
            emit(new Ir.Bin(shifted, Ir.BinOp.SUB, subject, new Ir.Imm(lowest)));
            biased = shifted;
        }

        // One unsigned comparison covers both ends: anything below the lowest label
        // wraps to a very large number and fails the same test.
        Ir.BasicBlock inRange = newBlock("switch_dispatch");
        emit(new Ir.Cbr(Ir.Cond.UGT, biased, new Ir.Imm(span - 1),
                defaultBlock.label(), inRange.label()));

        List<String> targets = new ArrayList<>(span);
        for (int value = lowest; value <= highest; value++) {
            String target = defaultBlock.label();
            for (int i = 0; i < arms.size(); i++) {
                if (!arms.get(i).isDefault() && caseValue(arms.get(i)) == value) {
                    target = armBlocks.get(i).label();
                    break;
                }
            }
            targets.add(target);
        }

        String tableLabel = mangler.internal("jumptable");
        module.jumpTables().add(new Ir.JumpTable(tableLabel, targets));

        startBlock(inRange);
        emit(new Ir.TableBr(biased, tableLabel, targets));
    }

    private void emitChainDispatch(Ir.Value subject, List<SwitchCase> arms,
                                   List<Ir.BasicBlock> armBlocks,
                                   Ir.BasicBlock defaultBlock) {
        for (int i = 0; i < arms.size(); i++) {
            if (arms.get(i).isDefault()) continue;
            Ir.BasicBlock next = newBlock("switch_test");
            emit(new Ir.Cbr(Ir.Cond.EQ, subject, new Ir.Imm(caseValue(arms.get(i))),
                    armBlocks.get(i).label(), next.label()));
            startBlock(next);
        }
        branchTo(defaultBlock);
    }

    private void lowerIf(If statement) {
        Ir.BasicBlock thenBlock = newBlock("then");
        Ir.BasicBlock elseBlock = newBlock("else");
        Ir.BasicBlock done = newBlock("endif");

        // The condition branches directly; no truth value is ever materialised.
        lowerCondition(statement.condition(), thenBlock,
                statement.elseBranch().isPresent() ? elseBlock : done);

        startBlock(thenBlock);
        lowerStatement(statement.thenBranch());
        branchTo(done);

        if (statement.elseBranch().isPresent()) {
            startBlock(elseBlock);
            lowerStatement(statement.elseBranch().get());
            branchTo(done);
        }

        startBlock(done);
    }

    private void lowerWhile(While statement) {
        Ir.BasicBlock head = newBlock("while_head");
        Ir.BasicBlock body = newBlock("while_body");
        Ir.BasicBlock done = newBlock("while_end");

        branchTo(head);
        startBlock(head);
        lowerCondition(statement.condition(), body, done);

        startBlock(body);
        loops.push(new LoopTargets(head, done));
        lowerStatement(statement.body());
        loops.pop();
        branchTo(head);          // the back edge

        startBlock(done);
    }

    private void lowerDoWhile(DoWhile statement) {
        Ir.BasicBlock body = newBlock("do_body");
        // `continue` runs the test, so it targets the test block rather than the body.
        Ir.BasicBlock test = newBlock("do_test");
        Ir.BasicBlock done = newBlock("do_end");

        branchTo(body);
        startBlock(body);
        loops.push(new LoopTargets(test, done));
        lowerStatement(statement.body());
        loops.pop();
        branchTo(test);

        startBlock(test);
        lowerCondition(statement.condition(), body, done);   // the back edge

        startBlock(done);
    }

    private void lowerFor(For statement) {
        Ir.BasicBlock head = newBlock("for_head");
        Ir.BasicBlock body = newBlock("for_body");
        // `continue` in a for loop must still run the update, so it targets its own
        // block rather than the loop head.
        Ir.BasicBlock update = newBlock("for_update");
        Ir.BasicBlock done = newBlock("for_end");

        statement.initializer().ifPresent(item -> {
            switch (item) {
                case Declaration d -> lowerDeclaration(d);
                case EnumDeclaration ignored -> { }   // names for numbers; no storage
                case Statement s -> lowerStatement(s);
            }
        });

        branchTo(head);
        startBlock(head);
        if (statement.condition().isPresent()) {
            lowerCondition(statement.condition().get(), body, done);
        } else {
            branchTo(body);      // `for (;;)` loops forever
        }

        startBlock(body);
        loops.push(new LoopTargets(update, done));
        lowerStatement(statement.body());
        loops.pop();
        branchTo(update);

        startBlock(update);
        statement.update().ifPresent(this::lowerForEffect);
        branchTo(head);          // the back edge

        startBlock(done);
    }

    private void lowerBreak(Break statement) {
        if (loops.isEmpty()) return;      // analysis already reported it
        branchTo(loops.peek().breakTarget());
    }

    private void lowerContinue(Continue statement) {
        if (loops.isEmpty()) return;
        branchTo(loops.peek().continueTarget());
    }

    private void lowerReturn(Return statement) {
        if (block.isTerminated()) return;
        Ir.Value value = statement.value().isPresent()
                ? lowerExpression(statement.value().get())
                : null;
        emit(new Ir.Ret(value));
    }

    private void emitStore(Symbol symbol, Ir.Value value) {
        Ir.Addr addr = addressOf(symbol);
        if (addr != null) emit(new Ir.Store(addr, value, widthOfSymbol(symbol)));
    }

    /**
     * The access width for a symbol's storage.
     *
     * <p>A parameter is always a full word, whatever its declared type: the caller
     * pushes words, and words on this machine are big-endian, so reading a byte
     * parameter with MOVB at the slot address would fetch the HIGH byte — which is
     * always zero. Narrowing happens at the call site instead, where the value is
     * still in a register.
     */
    private static Ir.Width widthOfSymbol(Symbol symbol) {
        if (symbol instanceof Symbol.ParamVar) return Ir.Width.WORD;
        return widthOf(symbol.type());
    }

    private Ir.Addr addressOf(Symbol symbol) {
        if (symbol instanceof Symbol.LocalVar local && isPromotable(local)) {
            promotable.add(local.slot());
        }
        if (symbol instanceof Symbol.ParamVar param && isPromotable(param)) {
            promotable.add(param.slot());
        }
        return switch (symbol) {
            case Symbol.LocalVar v -> new Ir.Addr.Local(v.slot());
            // Argument 0 arrived in a register and was written to a slot of its own;
            // the rest are where the caller pushed them.
            case Symbol.ParamVar v -> v.inRegister()
                    ? new Ir.Addr.Local(v.slot())
                    : new Ir.Addr.Arg(v.index());
            case Symbol.GlobalVar v -> new Ir.Addr.Global(v.label());
            case Symbol.Func ignored -> null;
            case Symbol.Constant ignored -> null;   // a value, with nowhere to live
        };
    }

    /**
     * Whether a local may live in a register instead of the frame.
     *
     * <p>Arrays and structs are addressed by offset and are wider than a register in
     * the first place; a byte variable must truncate on store, which a register copy
     * would not; and taking a variable's address means a pointer could reach memory
     * this would stop writing to.
     */
    /**
     * Whether the register argument may stay in a register.
     *
     * <p>Only the address being taken stops it. A parameter is a full word in its slot
     * whatever its declared type — the caller narrowed it — so there is no store to
     * truncate, and a struct or an array cannot be a parameter at all.
     */
    private boolean isPromotable(Symbol.ParamVar param) {
        return param.inRegister() && !info.isAddressTaken(param);
    }

    private boolean isPromotable(Symbol.LocalVar local) {
        if (info.isAddressTaken(local)) return false;
        if (local.type() instanceof Type.Arr) return false;
        if (local.type().isStruct()) return false;
        return !local.type().isByteWidth();
    }

    private static Ir.Width widthOf(Type type) {
        return type.isByteWidth() ? Ir.Width.BYTE : Ir.Width.WORD;
    }

    /* ---------------- expressions ---------------- */

    private Ir.Value lowerExpression(Expression expression) {
        return switch (expression) {
            case Constant c -> new Ir.Imm(c.value());
            case Identifier id -> lowerIdentifier(id);
            case Unary u -> lowerUnary(u);
            case Binary b -> lowerBinary(b);
            case Logical l -> lowerLogical(l);
            case Assign a -> lowerAssign(a);
            case Call c -> lowerCall(c);
            case IndirectCall c -> lowerIndirectCall(c);
            case StringLiteral s -> new Ir.GlobalRef(internString(s.value()));
            case AddressOf a -> lowerAddressOf(a);
            // A function reached through a pointer is still its address: C lets `*fp`
            // be called, compared or assigned, and none of those read the code.
            case Deref d -> info.typeOf(d) instanceof Type.Func
                    ? lowerExpression(d.operand())
                    : lowerPlace(d);
            case Index i -> lowerPlace(i);
            case Member m -> lowerPlace(m);
            case SizeOf z -> new Ir.Imm(info.sizeOf(z));
            case PostfixUpdate u -> lowerPostfixUpdate(u);
            case Cast c -> lowerCast(c);
            case Conditional c -> lowerConditional(c);
            case Comma c -> lowerComma(c);
        };
    }

    /** `a, b` evaluates `a` for its effects and yields `b`. */
    private Ir.Value lowerComma(Comma comma) {
        lowerForEffect(comma.left());
        return lowerExpression(comma.right());
    }

    /**
     * An expression whose value nothing reads: a statement, a {@code for} loop's step,
     * the left of a comma. Only an assignment lowers differently for it, by not
     * narrowing a value that would be thrown away.
     */
    private void lowerForEffect(Expression expression) {
        if (expression instanceof Assign assign) {
            lowerAssign(assign, false);
        } else {
            lowerExpression(expression);
        }
    }

    private Ir.Value lowerIdentifier(Identifier identifier) {
        Symbol symbol = info.symbolOf(identifier);
        if (symbol == null) return new Ir.Imm(0);
        if (symbol instanceof Symbol.Constant constant) return new Ir.Imm(constant.value());
        if (symbol instanceof Symbol.Func function) return new Ir.GlobalRef(function.label());
        // A const whose initializer folded holds that value for the whole run, so a
        // read of it is the value — narrowed to its type already, the way a load
        // would narrow it — exactly as an enumerator is. Its storage is still there
        // for anything that takes its address, and dropped when nothing does.
        java.util.Optional<Integer> known = info.knownValueOf(symbol);
        if (known.isPresent()) return new Ir.Imm(known.get());
        Ir.Addr addr = addressOf(symbol);
        if (addr == null) return new Ir.Imm(0);

        // An array or a struct used as a value decays to the address of its first
        // byte, rather than being loaded: neither fits in a register.
        if (symbol.type() instanceof Type.Arr || symbol.type().isStruct()) {
            Ir.VReg address = freshVreg();
            emit(new Ir.AddrOf(address, addr));
            return address;
        }

        Ir.VReg dst = freshVreg();
        emit(new Ir.Load(dst, addr, widthOfSymbol(symbol), isSignedByte(symbol.type())));
        return dst;
    }

    /**
     * Reading a place.
     *
     * <p>A scalar is loaded. An array or a struct is not: neither has a
     * register-sized value, so each yields its own address instead, which is what
     * everything that can legally consume one wants. That is the same rule
     * {@link #lowerIdentifier} applies, and it has to be, because the place may be a
     * member: {@code h.cells} is an array just as surely as a local named
     * {@code cells} is, and loading from it instead of taking its address gave
     * {@code h.cells[i]} a base of whatever happened to be in the struct's first two
     * bytes. Both the read and the write agreed on that wrong base, so the array
     * behaved until it landed on something that mattered — the entry stub, in the
     * case that found this.
     */
    private Ir.Value lowerPlace(Expression place) {
        Type type = info.typeOf(place);
        Ir.Addr addr = addressOfPlace(place);
        if (!(type instanceof Type.Arr) && !type.isStruct()) return lowerLoadFrom(addr, type);

        Ir.VReg address = freshVreg();
        emit(new Ir.AddrOf(address, addr));
        return address;
    }

    private Ir.Value lowerAssign(Assign assign) {
        return lowerAssign(assign, true);
    }

    /**
     * {@code target = value}, and the compound forms the parser makes into one.
     *
     * <p>A compound assignment's value reads its own target, and when that target is a
     * computed place the address is worked out <em>once</em>, here, and shared with
     * the read through {@link #sharedPlaces}: {@code a[next()] += 1} calls
     * {@code next} once, and {@code ++buf[pos++]} steps {@code pos} once. The same
     * care {@link #lowerPostfixUpdate} takes, and for the same reason.
     *
     * @param used whether anything reads the assignment's own value, which is what
     *             the target holds afterwards — so for a byte it is narrowed, and
     *             {@code ++b} at 255 is 0, not 256. Nothing is narrowed for a
     *             statement, where the value is thrown away.
     */
    private Ir.Value lowerAssign(Assign assign, boolean used) {
        Type targetType = info.typeOf(assign.target());
        if (targetType.isStruct()) return lowerStructAssign(assign, targetType.asStruct());

        // Assignment through a pointer, a subscript or a member goes to a computed
        // address.
        boolean computedPlace = assign.target() instanceof Deref
                || assign.target() instanceof Index || assign.target() instanceof Member;

        Ir.Addr shared = null;
        if (assign.compound() && computedPlace) {
            shared = addressOfPlace(assign.target());
            sharedPlaces.put(assign.target(), shared);
        }
        Ir.Value value;
        try {
            value = lowerExpression(assign.value());
        } finally {
            if (shared != null) sharedPlaces.remove(assign.target());
        }

        if (computedPlace) {
            Ir.Addr addr = shared != null ? shared : addressOfPlace(assign.target());
            emit(new Ir.Store(addr, value, widthOfType(targetType)));
        } else {
            Symbol symbol = info.symbolOf(assign);
            if (symbol == null) return value;
            emitStore(symbol, value);
        }
        // What the target now holds, so `x = y = 1` works and a byte wraps.
        return used ? narrowTo(targetType, value) : value;
    }

    /**
     * {@code cond ? a : b}, as a branch that merges — the same shape {@code &&} and
     * {@code ||} already use, so only the arm that is taken is evaluated.
     */
    private Ir.Value lowerConditional(Conditional conditional) {
        Ir.VReg result = freshVreg();
        Ir.BasicBlock thenBlock = newBlock("cond_then");
        Ir.BasicBlock elseBlock = newBlock("cond_else");
        Ir.BasicBlock done = newBlock("cond_done");

        lowerCondition(conditional.condition(), thenBlock, elseBlock);

        startBlock(thenBlock);
        emit(new Ir.Copy(result, lowerExpression(conditional.then())));
        branchTo(done);

        startBlock(elseBlock);
        emit(new Ir.Copy(result, lowerExpression(conditional.otherwise())));
        branchTo(done);

        startBlock(done);
        return result;
    }

    /**
     * {@code (type)value}.
     *
     * <p>Only a narrowing to a byte width does any work, and {@code narrowTo} already
     * knows how — mask, and sign-extend again if the target is signed. Everything else
     * is a reinterpretation of the same sixteen bits: {@code word} to {@code sword},
     * any pointer to any other, a byte widening that was already zero- or
     * sign-extended when it was loaded.
     */
    private Ir.Value lowerCast(Cast cast) {
        Ir.Value value = lowerExpression(cast.operand());
        Type to = info.typeOf(cast);
        if (to.isVoid()) return new Ir.Imm(0);
        return narrowTo(to, value);
    }

    /**
     * {@code x++} and {@code x--}: update the place, produce what it held before.
     *
     * <p>For a place reached through a pointer, a subscript or a member the address is
     * computed <em>once</em> and used for both the load and the store. That is not an
     * optimisation, it is the difference between {@code a[next()]++} stepping one
     * element and stepping two.
     */
    private Ir.Value lowerPostfixUpdate(PostfixUpdate update) {
        Type type = info.typeOf(update.target());
        Ir.BinOp op = update.increment() ? Ir.BinOp.ADD : Ir.BinOp.SUB;
        Ir.Value step = new Ir.Imm(stepFor(type));

        if (update.target() instanceof Deref || update.target() instanceof Index
                || update.target() instanceof Member) {
            Ir.Addr addr = addressOfPlace(update.target());
            Ir.VReg before = freshVreg();
            emit(new Ir.Load(before, addr, widthOfType(type), isSignedByte(type)));
            Ir.VReg after = freshVreg();
            emit(new Ir.Bin(after, op, before, step));
            emit(new Ir.Store(addr, after, widthOfType(type)));
            return before;
        }

        Symbol symbol = info.symbolOf(update);
        if (symbol == null) return new Ir.Imm(0);
        Ir.Addr addr = addressOf(symbol);
        if (addr == null) return new Ir.Imm(0);

        Ir.VReg before = freshVreg();
        emit(new Ir.Load(before, addr, widthOfSymbol(symbol), isSignedByte(symbol.type())));
        Ir.VReg after = freshVreg();
        emit(new Ir.Bin(after, op, before, step));
        emitStore(symbol, after);
        return before;
    }

    /**
     * How far one step moves. A pointer steps by whole elements, as C's does — so
     * {@code p++} on a {@code struct Node*} advances by the size of a node, and the
     * one on a {@code word*} by two.
     */
    private static int stepFor(Type type) {
        Type pointee = type.pointee();
        return pointee == null ? 1 : Math.max(1, pointee.size());
    }

    /**
     * {@code a = b} between two structs: a word-by-word copy, unrolled.
     *
     * <p>Unrolled rather than looped because a struct is at most 127 bytes here and
     * the loop would cost more than it saved for the two- and three-word structs that
     * are the whole point. There is no {@code memcpy} to call and nothing on this
     * machine that moves more than a word at a time.
     *
     * <p>The result is deliberately not the copied struct. C says it is, but using it
     * would mean a temporary to hold a value that has no register to live in, for an
     * expression nobody writes.
     */
    private Ir.Value lowerStructAssign(Assign assign, Type.Struct struct) {
        Ir.Addr destination = addressable(addressOfPlace(assign.target()));
        Ir.VReg source = asRegister(lowerExpression(assign.value()));

        int copied = 0;
        while (copied + 2 <= struct.size()) {
            Ir.VReg word = freshVreg();
            emit(new Ir.Load(word, new Ir.Addr.Mem(source, copied), Ir.Width.WORD));
            emit(new Ir.Store(displaced(destination, copied), word, Ir.Width.WORD));
            copied += 2;
        }
        // Nothing is padded, so a struct ending in a byte really is an odd size.
        if (copied < struct.size()) {
            Ir.VReg last = freshVreg();
            emit(new Ir.Load(last, new Ir.Addr.Mem(source, copied), Ir.Width.BYTE));
            emit(new Ir.Store(displaced(destination, copied), last, Ir.Width.BYTE));
        }
        return new Ir.Imm(0);
    }

    /** An address a displacement can be added to, materialising a label if need be. */
    private Ir.Addr addressable(Ir.Addr addr) {
        if (!(addr instanceof Ir.Addr.Global)) return addr;
        // `[label+2]` is a syntax error on this machine, so a global being written
        // past its first word has to go through a register.
        Ir.VReg address = freshVreg();
        emit(new Ir.AddrOf(address, addr));
        return new Ir.Addr.Mem(address, 0);
    }

    private static Ir.Addr displaced(Ir.Addr addr, int delta) {
        return switch (addr) {
            case Ir.Addr.Local local -> new Ir.Addr.Local(local.slot(), local.offset() + delta);
            case Ir.Addr.Mem mem -> new Ir.Addr.Mem(mem.base(), mem.offset() + delta);
            default -> throw new IllegalStateException("cannot displace " + addr);
        };
    }

    /**
     * Places whose address a compound assignment has already computed, by node, so
     * that the read inside its value uses that address rather than working it out —
     * and running its side effects — a second time. See {@link #lowerAssign}.
     */
    private final Map<Expression, Ir.Addr> sharedPlaces = new IdentityHashMap<>();

    /**
     * The address an assignable expression denotes.
     *
     * <p>{@code a[i]} is exactly {@code *(a + i)}, so both forms end up here as a
     * base value plus a scaled offset.
     */
    private Ir.Addr addressOfPlace(Expression place) {
        Ir.Addr shared = sharedPlaces.get(place);
        if (shared != null) return shared;
        return switch (place) {
            case Deref d -> new Ir.Addr.Mem(asRegister(lowerExpression(d.operand())), 0);
            case Index idx -> {
                Ir.Value base = lowerExpression(idx.base());
                Ir.Value index = lowerExpression(idx.index());
                Type element = info.typeOf(idx);
                yield new Ir.Addr.Mem(asRegister(scaledAdd(base, index, element.size())), 0);
            }
            case Member member -> addressOfMember(member);
            case Identifier id -> {
                Symbol symbol = info.symbolOf(id);
                Ir.Addr addr = symbol == null ? null : addressOf(symbol);
                yield addr == null ? new Ir.Addr.Local(0) : addr;
            }
            default -> throw new IllegalStateException("not an assignable place: " + place);
        };
    }

    /**
     * Where {@code s.field} or {@code p->field} lives.
     *
     * <p>Both are the same sum — a base address plus the member's fixed offset — and
     * the only question is where the base comes from. Through a pointer it is the
     * pointer's value. Otherwise it is wherever the struct itself is, and that case
     * is worth folding rather than computing: a member of a local struct becomes
     * {@code [D+k]} with the offset already added, one instruction, exactly what a
     * plain local costs.
     */
    private Ir.Addr addressOfMember(Member member) {
        int offset = fieldOf(member).offset();

        if (member.throughPointer()) {
            Ir.Value pointer = lowerExpression(member.base());
            return new Ir.Addr.Mem(asRegister(pointer), offset);
        }

        Ir.Addr base = addressOfPlace(member.base());
        return switch (base) {
            // A frame slot takes a displacement, so the member costs nothing extra.
            case Ir.Addr.Local local -> new Ir.Addr.Local(local.slot(), local.offset() + offset);
            case Ir.Addr.Mem mem -> new Ir.Addr.Mem(mem.base(), mem.offset() + offset);
            // A direct label takes no displacement on this machine — `[g_s+2]` is a
            // syntax error — so a global's members go through a register, exactly as
            // a global array's elements already do.
            default -> {
                Ir.VReg address = freshVreg();
                emit(new Ir.AddrOf(address, base));
                yield new Ir.Addr.Mem(address, offset);
            }
        };
    }

    private Type.Field fieldOf(Member member) {
        Type base = info.typeOf(member.base());
        Type.Struct struct = member.throughPointer()
                ? (base.pointee() == null ? null : base.pointee().asStruct())
                : base.asStruct();
        if (struct == null) throw new IllegalStateException("not a struct: " + base);
        Type.Field field = struct.field(member.field());
        if (field == null) throw new IllegalStateException("no member " + member.field());
        return field;
    }

    /** {@code base + index * scale}, skipping the multiply when scale is one. */
    private Ir.Value scaledAdd(Ir.Value base, Ir.Value index, int scale) {
        Ir.Value offset = index;
        if (scale != 1) {
            Ir.VReg scaled = freshVreg();
            emit(new Ir.Bin(scaled, Ir.BinOp.MUL, index, new Ir.Imm(scale)));
            offset = scaled;
        }
        Ir.VReg sum = freshVreg();
        emit(new Ir.Bin(sum, Ir.BinOp.ADD, base, offset));
        return sum;
    }

    /** Materialises a value into a virtual register, since Mem needs a base register. */
    private Ir.VReg asRegister(Ir.Value value) {
        if (value instanceof Ir.VReg vreg) return vreg;
        Ir.VReg dst = freshVreg();
        emit(new Ir.Copy(dst, value));
        return dst;
    }

    private Ir.Value lowerLoadFrom(Ir.Addr addr, Type type) {
        Ir.VReg dst = freshVreg();
        emit(new Ir.Load(dst, addr, widthOfType(type), isSignedByte(type)));
        return dst;
    }

    /**
     * Whether reading this type has to sign-extend.
     *
     * <p>Only {@code sbyte}: a {@code sword} already fills the register, and a
     * {@code byte} is not signed. A parameter is always a whole word on the stack, so
     * {@code widthOfSymbol} reports WORD for one and this is never asked about it.
     */
    private static boolean isSignedByte(Type type) {
        return type.isByteWidth() && type.isSigned();
    }

    private Ir.Value lowerAddressOf(AddressOf expression) {
        Expression operand = expression.operand();

        // &*p and &a[i] are just the address that was going to be computed anyway.
        if (operand instanceof Deref d) return lowerExpression(d.operand());
        if (operand instanceof Index idx) {
            Ir.Value base = lowerExpression(idx.base());
            Ir.Value index = lowerExpression(idx.index());
            return scaledAdd(base, index, info.typeOf(idx).size());
        }
        // &s.field and &p->field: the very address a load or a store of that member
        // would use, which is already worked out in one place.
        if (operand instanceof Member member) {
            Ir.VReg dst = freshVreg();
            emit(new Ir.AddrOf(dst, addressOfMember(member)));
            return dst;
        }

        Symbol symbol = (operand instanceof Identifier id) ? info.symbolOf(id) : null;

        // The address of a function is just its label; a label used as an operand
        // assembles to its address.
        if (symbol instanceof Symbol.Func function) {
            return new Ir.GlobalRef(function.label());
        }

        Ir.Addr addr = symbol == null ? null : addressOf(symbol);
        if (addr == null) {
            // Unreachable for a program that analysed: the analyzer rejects '&' of
            // anything that is not a place, and every kind of place is handled above.
            // It used to return zero here, which meant that the one kind it did not
            // handle -- a struct member -- silently became a null pointer instead of
            // an address, at every optimization level.
            throw new IllegalStateException(
                    "no address for the operand of '&': " + operand.getClass().getSimpleName());
        }

        Ir.VReg dst = freshVreg();
        emit(new Ir.AddrOf(dst, addr));
        return dst;
    }

    private static Ir.Width widthOfType(Type type) {
        return type.isByteWidth() ? Ir.Width.BYTE : Ir.Width.WORD;
    }

    /** Adds a string to the module's read-only data, reusing an identical one. */
    private String internString(String value) {
        return strings.computeIfAbsent(value, v -> mangler.internal("str"));
    }

    private Ir.Value lowerCall(Call call) {
        Symbol symbol = info.symbolOf(call);
        List<Ir.Value> arguments = new ArrayList<>(call.arguments().size());
        for (Expression argument : call.arguments()) {
            arguments.add(lowerExpression(argument));
        }

        // A builtin becomes one instruction, not a call.
        if (Builtins.isBuiltin(call.callee())) {
            return lowerBuiltin(call.callee(), arguments);
        }
        if (!(symbol instanceof Symbol.Func function)) {
            // A variable holding a function pointer, called by its name.
            Type.Func signature = symbol == null ? null : symbol.type().callable();
            if (signature == null) return new Ir.Imm(0);
            Ir.VReg callee = freshVreg();
            emit(new Ir.Load(callee, addressOf(symbol), widthOfSymbol(symbol), false));
            return callThrough(callee, signature, arguments);
        }

        // A byte parameter is passed in a word, so the conversion has to happen here
        // while the value is still in a register.
        List<Type> parameterTypes = function.parameterTypes();
        for (int i = 0; i < arguments.size() && i < parameterTypes.size(); i++) {
            arguments.set(i, narrowTo(parameterTypes.get(i), arguments.get(i)));
        }

        Ir.VReg dst = function.returnType().isVoid() ? null : freshVreg();
        emit(new Ir.Call(dst, function.label(), arguments));
        return dst == null ? new Ir.Imm(0) : dst;
    }

    private Ir.Value lowerIndirectCall(IndirectCall call) {
        List<Ir.Value> arguments = new ArrayList<>(call.arguments().size());
        for (Expression argument : call.arguments()) arguments.add(lowerExpression(argument));
        Ir.Value callee = lowerExpression(call.callee());
        return callThrough(callee, info.typeOf(call.callee()).callable(), arguments);
    }

    /** A call to whatever address {@code callee} holds, narrowed as a direct one is. */
    private Ir.Value callThrough(Ir.Value callee, Type.Func signature, List<Ir.Value> arguments) {
        for (int i = 0; i < arguments.size() && i < signature.parameters().size(); i++) {
            arguments.set(i, narrowTo(signature.parameters().get(i), arguments.get(i)));
        }
        Ir.VReg dst = signature.returnType().isVoid() ? null : freshVreg();
        emit(new Ir.Call(dst, null, callee, arguments));
        return dst == null ? new Ir.Imm(0) : dst;
    }

    private Ir.Value lowerBuiltin(String name, List<Ir.Value> arguments) {
        // The allocator is too big to inline, so these are ordinary calls into a
        // runtime helper — emitted only when a program actually allocates.
        if (Builtins.isHeapBuiltin(name)) {
            String helper = Builtins.ALLOC.equals(name) ? Runtime.ALLOC : Runtime.FREE;
            module.runtimeHelpers().add(helper);
            module.setUsesHeap(true);
            Ir.VReg dst = Builtins.ALLOC.equals(name) ? freshVreg() : null;
            emit(new Ir.Call(dst, helper, arguments));
            return dst == null ? new Ir.Imm(0) : dst;
        }

        // The machine and library helpers. Each is a plain call, so the ordinary
        // convention carries the arguments, and each is emitted only when referenced — a
        // program that never scrolls a tile map pays nothing for __vwrite existing.
        if (Builtins.isRuntimeCall(name)) {
            String helper = switch (name) {
                case Builtins.VWRITE -> Runtime.VWRITE;
                case Builtins.VREAD -> Runtime.VREAD;
                case Builtins.VFILL -> Runtime.VFILL;
                case Builtins.WAITFRAME -> Runtime.WAITFRAME;
                case Builtins.MULHI -> Runtime.MULHI;
                case Builtins.GETKEY -> Runtime.GETKEY;
                case Builtins.MEMCPY -> Runtime.MEMCPY;
                case Builtins.MEMSET -> Runtime.MEMSET;
                case Builtins.STRLEN -> Runtime.STRLEN;
                case Builtins.STRCPY -> Runtime.STRCPY;
                case Builtins.STRCMP -> Runtime.STRCMP;
                case Builtins.SQRT -> Runtime.SQRT;
                case Builtins.SIN -> Runtime.SIN;
                case Builtins.COS -> Runtime.COS;
                case Builtins.FIXMUL -> Runtime.FIXMUL;
                default -> throw new IllegalStateException("unmapped runtime builtin " + name);
            };
            module.runtimeHelpers().add(helper);
            Ir.VReg dst = Builtins.returnTypeOf(name).isVoid() ? null : freshVreg();
            emit(new Ir.Call(dst, helper, arguments));
            return dst == null ? new Ir.Imm(0) : dst;
        }

        switch (name) {
            case Builtins.IN -> {
                Ir.VReg dst = freshVreg();
                emit(new Ir.In(dst, arguments.get(0)));
                return dst;
            }
            case Builtins.OUT -> emit(new Ir.Out(arguments.get(0), arguments.get(1)));
            case Builtins.TICKS -> {
                // TMRCOUNTER. This is an ordinary port read with the port already
                // decided, so it reuses Ir.In rather than earning a node of its own.
                Ir.VReg dst = freshVreg();
                emit(new Ir.In(dst, new Ir.Imm(4)));
                return dst;
            }
            case Builtins.RANDOM -> {
                // RNDGEN, a fresh word on every read. Like __ticks, a port read whose
                // port is already decided.
                Ir.VReg dst = freshVreg();
                emit(new Ir.In(dst, new Ir.Imm(10)));
                return dst;
            }
            case Builtins.HALT -> emit(new Ir.Flag(Ir.FlagOp.HALT));
            case Builtins.STI -> emit(new Ir.Flag(Ir.FlagOp.ENABLE_INTERRUPTS));
            case Builtins.CLI -> emit(new Ir.Flag(Ir.FlagOp.DISABLE_INTERRUPTS));
            case Builtins.SETISR -> {
                // The vector is an ordinary global, so installing a handler is an
                // ordinary store — which is what makes it swappable at run time.
                module.setUsesInterrupts(true);
                emit(new Ir.Store(new Ir.Addr.Global(ISR_VECTOR_LABEL),
                        arguments.get(0), Ir.Width.WORD));
            }
            default -> throw new IllegalStateException("unknown builtin " + name);
        }
        return new Ir.Imm(0);
    }

    private Ir.Value lowerUnary(Unary unary) {
        return switch (unary.operation()) {
            case PLUS -> lowerExpression(unary.operand());
            case NEGATE -> {
                Ir.Value operand = lowerExpression(unary.operand());
                Ir.VReg dst = freshVreg();
                emit(new Ir.Un(dst, Ir.UnOp.NEG, operand));
                yield dst;
            }
            case COMPLEMENT -> {
                Ir.Value operand = lowerExpression(unary.operand());
                Ir.VReg dst = freshVreg();
                emit(new Ir.Un(dst, Ir.UnOp.NOT, operand));
                yield dst;
            }
            // `!x` is exactly `x == 0`, which the comparison path already handles.
            case NOT -> {
                Ir.Value operand = lowerExpression(unary.operand());
                Ir.VReg dst = freshVreg();
                emit(new Ir.Cmp(dst, Ir.Cond.EQ, operand, new Ir.Imm(0)));
                yield dst;
            }
        };
    }

    private Ir.Value lowerBinary(Binary binary) {
        if (binary.operation().isComparison()) {
            Ir.Value lhs = lowerExpression(binary.left());
            Ir.Value rhs = lowerExpression(binary.right());
            Ir.VReg dst = freshVreg();
            emitCompare(dst, conditionFor(binary), lhs, rhs);
            return dst;
        }

        Ir.Value lhs = lowerExpression(binary.left());
        Ir.Value rhs = lowerExpression(binary.right());

        // Pointer arithmetic counts in elements, so scale the integer side by the
        // element size before adding.
        Type leftType = info.typeOf(binary.left()).promoted();
        Type rightType = info.typeOf(binary.right()).promoted();
        if (leftType.isPointer() && rightType.isInteger()) {
            int scale = elementSize(leftType);
            if (scale != 1) rhs = scale(rhs, scale);
        } else if (rightType.isPointer() && leftType.isInteger()) {
            int scale = elementSize(rightType);
            if (scale != 1) lhs = scale(lhs, scale);
        }

        Ir.BinOp op = switch (binary.operation()) {
            case ADD -> Ir.BinOp.ADD;
            case SUBTRACT -> Ir.BinOp.SUB;
            case MULTIPLY -> Ir.BinOp.MUL;
            case DIVIDE -> Ir.BinOp.DIV;
            case MODULO -> Ir.BinOp.MOD;
            case BIT_AND -> Ir.BinOp.AND;
            case BIT_OR -> Ir.BinOp.OR;
            case BIT_XOR -> Ir.BinOp.XOR;
            case SHIFT_LEFT -> Ir.BinOp.SHL;
            case SHIFT_RIGHT -> Ir.BinOp.SHR;
            default -> throw new IllegalStateException("comparison handled above");
        };

        if ((op == Ir.BinOp.DIV || op == Ir.BinOp.MOD)
                && rhs instanceof Ir.Imm imm && imm.value() == 0) {
            reporter.error(binary.span(),
                    binary.operation() == BinaryOperation.MODULO
                            ? "remainder by zero"
                            : "division by zero");
        }

        // Signed division, remainder and arithmetic shift have no instruction on
        // this machine, so they become calls to out-of-line runtime helpers. A shift
        // is signed by its left operand alone, as in C — and as the constant folder
        // has it, which is what keeps a folded shift and a run-time one the same.
        boolean signed = op == Ir.BinOp.SHR
                ? leftType.isSigned()
                : leftType.isSigned() || rightType.isSigned();
        String helper = signed ? helperFor(op) : null;
        if (helper != null) {
            module.runtimeHelpers().add(helper);
            Ir.VReg result = freshVreg();
            emit(new Ir.Call(result, helper, List.of(lhs, rhs)));
            return result;
        }

        Ir.VReg dst = freshVreg();
        emit(new Ir.Bin(dst, op, lhs, rhs));
        return dst;
    }

    private static String helperFor(Ir.BinOp op) {
        return switch (op) {
            case DIV -> Runtime.SDIV;
            case MOD -> Runtime.SMOD;
            case SHR -> Runtime.SAR;
            default -> null;
        };
    }

    /**
     * Short-circuit {@code &&} and {@code ||}.
     *
     * <p>The result is built in a frame slot rather than a virtual register because
     * the two arms write it from different blocks, and without SSA there is no phi
     * to merge them. A slot is the honest way to express that, and costs one store
     * on each path.
     */
    private Ir.Value lowerLogical(Logical logical) {
        Ir.VReg result = freshVreg();
        Ir.BasicBlock evaluateRight = newBlock("sc_rhs");
        Ir.BasicBlock setTrue = newBlock("sc_true");
        Ir.BasicBlock setFalse = newBlock("sc_false");
        Ir.BasicBlock done = newBlock("sc_done");

        if (logical.operation() == LogicalOperation.AND) {
            // false && _  is false without evaluating the right side
            lowerCondition(logical.left(), evaluateRight, setFalse);
        } else {
            lowerCondition(logical.left(), setTrue, evaluateRight);
        }

        startBlock(evaluateRight);
        lowerCondition(logical.right(), setTrue, setFalse);

        startBlock(setTrue);
        emit(new Ir.Copy(result, new Ir.Imm(1)));
        branchTo(done);

        startBlock(setFalse);
        emit(new Ir.Copy(result, new Ir.Imm(0)));
        branchTo(done);

        startBlock(done);
        return result;
    }

    /**
     * Lowers {@code expression} as a condition, branching directly to one of two
     * blocks. Comparisons become a fused {@link Ir.Cbr}; anything else is compared
     * against zero.
     */
    void lowerCondition(Expression expression, Ir.BasicBlock ifTrue, Ir.BasicBlock ifFalse) {
        switch (expression) {
            case Binary b when b.operation().isComparison() -> {
                Ir.Value lhs = lowerExpression(b.left());
                Ir.Value rhs = lowerExpression(b.right());
                emitBranch(conditionFor(b), lhs, rhs, ifTrue.label(), ifFalse.label());
            }
            case Unary u when u.operation() == dev.madlador.parser.ast.UnaryOperation.NOT ->
                // `if (!x)` just swaps the destinations; no value is ever built.
                    lowerCondition(u.operand(), ifFalse, ifTrue);
            case Logical l -> {
                Ir.BasicBlock evaluateRight = newBlock("cond_rhs");
                if (l.operation() == LogicalOperation.AND) {
                    lowerCondition(l.left(), evaluateRight, ifFalse);
                } else {
                    lowerCondition(l.left(), ifTrue, evaluateRight);
                }
                startBlock(evaluateRight);
                lowerCondition(l.right(), ifTrue, ifFalse);
            }
            default -> {
                Ir.Value value = lowerExpression(expression);
                emit(new Ir.Cbr(Ir.Cond.NE, value, new Ir.Imm(0),
                        ifTrue.label(), ifFalse.label()));
            }
        }
    }

    /**
     * A comparison into {@code dst}, as {@link #emitBranch} does it.
     */
    private void emitCompare(Ir.VReg dst, Ir.Cond cond, Ir.Value lhs, Ir.Value rhs) {
        if (!cond.isSigned()) {
            emit(new Ir.Cmp(dst, cond, lhs, rhs));
            return;
        }
        emit(new Ir.Cmp(dst, unsignedOf(cond), biased(lhs), biased(rhs)));
    }

    /**
     * A conditional branch, with a signed comparison spelled out the way the machine
     * has to do one: both sides biased by 0x8000, which maps signed order onto unsigned,
     * then compared unsigned. It used to be the instruction selector that biased, which
     * hid the work from every pass: a constant's bias was two instructions at run time
     * rather than none, and the bias of a side that never changes inside a loop was paid
     * on every iteration where once would do. In the IR, constant folding biases the
     * constant and loop-invariant code motion hoists the rest.
     */
    private void emitBranch(Ir.Cond cond, Ir.Value lhs, Ir.Value rhs, String ifTrue, String ifFalse) {
        if (!cond.isSigned()) {
            emit(new Ir.Cbr(cond, lhs, rhs, ifTrue, ifFalse));
            return;
        }
        emit(new Ir.Cbr(unsignedOf(cond), biased(lhs), biased(rhs), ifTrue, ifFalse));
    }

    private Ir.Value biased(Ir.Value value) {
        if (value instanceof Ir.Imm imm) return new Ir.Imm((imm.value() ^ 0x8000) & 0xFFFF);
        Ir.VReg flipped = freshVreg();
        emit(new Ir.Bin(flipped, Ir.BinOp.XOR, value, new Ir.Imm(0x8000)));
        return flipped;
    }

    private static Ir.Cond unsignedOf(Ir.Cond cond) {
        return switch (cond) {
            case SLT -> Ir.Cond.ULT;
            case SLE -> Ir.Cond.ULE;
            case SGT -> Ir.Cond.UGT;
            case SGE -> Ir.Cond.UGE;
            default -> cond;
        };
    }

    private Ir.Cond conditionFor(Binary binary) {
        boolean signed = info.typeOf(binary.left()).promoted().isSigned()
                || info.typeOf(binary.right()).promoted().isSigned();

        return switch (binary.operation()) {
            case EQUAL -> Ir.Cond.EQ;
            case NOT_EQUAL -> Ir.Cond.NE;
            case LESS -> signed ? Ir.Cond.SLT : Ir.Cond.ULT;
            case LESS_EQUAL -> signed ? Ir.Cond.SLE : Ir.Cond.ULE;
            case GREATER -> signed ? Ir.Cond.SGT : Ir.Cond.UGT;
            case GREATER_EQUAL -> signed ? Ir.Cond.SGE : Ir.Cond.UGE;
            default -> throw new IllegalStateException("not a comparison: " + binary.operation());
        };
    }

    /** Converts a value to a narrower declared type, as an assignment would. */
    private Ir.Value narrowTo(Type type, Ir.Value value) {
        if (!type.isByteWidth()) return value;

        Ir.VReg masked = freshVreg();
        emit(new Ir.Bin(masked, Ir.BinOp.AND, value, new Ir.Imm(0xFF)));
        if (!type.isSigned()) return masked;

        // Sign-extend: (x ^ 0x80) - 0x80 turns the top bit of the byte back into a
        // sign across the whole word.
        Ir.VReg flipped = freshVreg();
        emit(new Ir.Bin(flipped, Ir.BinOp.XOR, masked, new Ir.Imm(0x80)));
        Ir.VReg extended = freshVreg();
        emit(new Ir.Bin(extended, Ir.BinOp.SUB, flipped, new Ir.Imm(0x80)));
        return extended;
    }

    private Ir.Value scale(Ir.Value value, int factor) {
        Ir.VReg scaled = freshVreg();
        emit(new Ir.Bin(scaled, Ir.BinOp.MUL, value, new Ir.Imm(factor)));
        return scaled;
    }

    private static int elementSize(Type pointer) {
        Type element = pointer.pointee();
        return element == null ? 1 : Math.max(1, element.size());
    }

    private Ir.VReg freshVreg() {
        return new Ir.VReg(nextVreg++);
    }
}
