package dev.madlador.parser.ast;

/**
 * Renders the syntax tree as indented text for {@code --dump-ast}.
 *
 * <p>Because {@link Node} is sealed, this switch is checked for exhaustiveness:
 * adding a node type breaks the build here rather than silently printing nothing.
 */
public final class AstPrinter {

    private final StringBuilder out = new StringBuilder();
    private int depth;

    private AstPrinter() {
    }

    public static String print(Node node) {
        AstPrinter p = new AstPrinter();
        p.node(node);
        return p.out.toString();
    }

    private void list(InitializerList list) {
        line("List");
        indented(() -> {
            for (Initializer item : list.items()) {
                if (item instanceof InitializerList nested) {
                    list(nested);
                } else {
                    node(((Initializer.Value) item).expression());
                }
            }
        });
    }

    private void node(Node n) {
        switch (n) {
            case Program p -> {
                line("Program");
                indented(() -> {
                    for (TopLevel item : p.items()) node(item);
                });
            }
            case GlobalDeclaration g -> {
                line("Global " + g.name() + ": " + g.type().display());
                indented(() -> {
                    g.initializer().ifPresent(this::node);
                    g.list().ifPresent(this::list);
                });
            }
            case Call c -> {
                line("Call " + c.callee());
                indented(() -> {
                    for (Expression argument : c.arguments()) node(argument);
                });
            }
            case FunctionDefinition f -> {
                line("Function " + f.name() + " -> " + f.returnType().display());
                indented(() -> {
                    for (Parameter param : f.parameters()) node(param);
                    node(f.body());
                });
            }
            case FunctionDeclaration d -> {
                line("Prototype " + d.name() + " -> " + d.returnType().display());
                indented(() -> {
                    for (Parameter param : d.parameters()) node(param);
                });
            }
            case Parameter p -> line("Param " + (p.name() == null ? "(unnamed)" : p.name())
                    + ": " + p.type().display());
            case Block b -> {
                line("Block");
                indented(() -> {
                    for (BlockItem item : b.items()) node(item);
                });
            }
            case Declaration d -> {
                line("Declare " + (d.isStatic() ? "static " : "") + d.name() + ": "
                        + d.type().display());
                indented(() -> {
                    d.initializer().ifPresent(this::node);
                    d.list().ifPresent(this::list);
                });
            }
            case ExpressionStatement e -> {
                line("Expr");
                indented(() -> node(e.expression()));
            }
            case Return r -> {
                line("Return");
                indented(() -> r.value().ifPresent(this::node));
            }
            case If s -> {
                line("If");
                indented(() -> {
                    node(s.condition());
                    node(s.thenBranch());
                    s.elseBranch().ifPresent(this::node);
                });
            }
            case While s -> {
                line("While");
                indented(() -> {
                    node(s.condition());
                    node(s.body());
                });
            }
            case DoWhile s -> {
                line("DoWhile");
                indented(() -> {
                    node(s.body());
                    node(s.condition());
                });
            }
            case For s -> {
                line("For");
                indented(() -> {
                    s.initializer().ifPresent(this::node);
                    s.condition().ifPresent(this::node);
                    s.update().ifPresent(this::node);
                    node(s.body());
                });
            }
            case Switch sw -> {
                line("Switch");
                indented(() -> {
                    node(sw.subject());
                    for (SwitchCase arm : sw.cases()) {
                        line(arm.isDefault() ? "Default" : "Case");
                        indented(() -> {
                            if (!arm.isDefault()) node(arm.label());
                            for (BlockItem item : arm.body()) node(item);
                        });
                    }
                });
            }
            case Goto g -> line("Goto " + g.label());
            case Labeled l -> {
                line("Label " + l.label());
                indented(() -> node(l.body()));
            }
            case Break ignored -> line("Break");
            case Continue ignored -> line("Continue");
            case Empty ignored -> line("Empty");
            case Assign a -> {
                line("Assign");
                indented(() -> {
                    node(a.target());
                    node(a.value());
                });
            }
            case Binary b -> {
                line("Binary " + b.operation());
                indented(() -> {
                    node(b.left());
                    node(b.right());
                });
            }
            case Logical l -> {
                line("Logical " + l.operation());
                indented(() -> {
                    node(l.left());
                    node(l.right());
                });
            }
            case Unary u -> {
                line("Unary " + u.operation());
                indented(() -> node(u.operand()));
            }
            case Constant c -> line("Constant " + c.value());
            case StringLiteral s -> line("String " + quote(s.value()));
            case AddressOf a -> {
                line("AddressOf");
                indented(() -> node(a.operand()));
            }
            case Deref d -> {
                line("Deref");
                indented(() -> node(d.operand()));
            }
            case Index i -> {
                line("Index");
                indented(() -> {
                    node(i.base());
                    node(i.index());
                });
            }
            case Member m -> {
                line("Member " + m.operator() + m.field());
                indented(() -> node(m.base()));
            }
            case SizeOf z -> {
                line("SizeOf");
                indented(() -> {
                    z.type().ifPresent(t -> line("Type " + t.display()));
                    z.operand().ifPresent(this::node);
                });
            }
            case Conditional c -> {
                line("Conditional");
                indented(() -> {
                    node(c.condition());
                    node(c.then());
                    node(c.otherwise());
                });
            }
            case Comma c -> {
                line("Comma");
                indented(() -> {
                    node(c.left());
                    node(c.right());
                });
            }
            case IndirectCall c -> {
                line("IndirectCall");
                indented(() -> {
                    node(c.callee());
                    for (Expression argument : c.arguments()) node(argument);
                });
            }
            case Cast c -> {
                line("Cast " + c.type().display());
                indented(() -> node(c.operand()));
            }
            case PostfixUpdate u -> {
                line("PostfixUpdate " + u.spelling());
                indented(() -> node(u.target()));
            }
            case EnumDeclaration e -> {
                line("Enum " + (e.name() == null ? "(anonymous)" : e.name()));
                indented(() -> {
                    for (Enumerator member : e.members()) {
                        line(member.name());
                        member.value().ifPresent(value -> indented(() -> node(value)));
                    }
                });
            }
            case StructDeclaration s -> {
                line((s.isUnion() ? "Union " : "Struct ") + s.name());
                indented(() -> {
                    for (Parameter member : s.members()) node(member);
                });
            }
            case Identifier i -> line("Identifier " + i.name());
        }
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace("\"", "\\\"") + '"';
    }

    private void indented(Runnable body) {
        depth++;
        body.run();
        depth--;
    }

    private void line(String text) {
        out.append("  ".repeat(depth)).append(text).append('\n');
    }
}
