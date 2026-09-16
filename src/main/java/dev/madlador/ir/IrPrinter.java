package dev.madlador.ir;

/**
 * Renders the IR for {@code --dump-ir}.
 *
 * <p>These dumps make better golden-test fixtures than generated assembly: they stay
 * stable as instruction selection and register allocation change, so a test asserting
 * IR shape does not have to be rewritten every time the optimiser improves.
 */
public final class IrPrinter {

    private IrPrinter() {
    }

    public static String print(Ir.Module module) {
        StringBuilder sb = new StringBuilder();
        for (Ir.Function function : module.functions()) {
            sb.append(print(function));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static String print(Ir.Function function) {
        StringBuilder sb = new StringBuilder();
        sb.append("function ").append(function.name())
                .append(" (").append(function.parameterCount()).append(" args, ")
                .append(function.slotCount()).append(" slots, ")
                .append(function.vregCount()).append(" vregs)\n");

        for (Ir.BasicBlock block : function.blocks()) {
            sb.append(block.label()).append(":\n");
            for (Ir.Instr instruction : block.instructions()) {
                sb.append("    ").append(instruction).append('\n');
            }
        }
        return sb.toString();
    }
}
