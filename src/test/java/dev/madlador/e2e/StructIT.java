package dev.madlador.e2e;

import dev.madlador.oracle.Mona;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Structs on the real machine, including the things that only go wrong when run.
 *
 * <p>The shapes worth executing rather than reading are the ones where an offset
 * could be right in the assembly and wrong in effect: a copy that shares storage
 * with its source, a member reached through two pointers, and a recursive type
 * walked to the bottom and back.
 */
@DisplayName("structs")
class StructIT {

    /** The memory-mapped text display: thirty-two characters at 0x1000. */
    private static final int[] DISPLAY = {0x1000, 0x1020};

    private static final int STACK_TOP = 0x0FFF;

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    private static Oracle.Result run(String source) {
        return Oracle.get().run(Mona.compile(source).assembly(), 500_000, DISPLAY);
    }

    private static String screen(Oracle.Result result) {
        StringBuilder sb = new StringBuilder();
        for (int b : result.memory()) sb.append(b == 0 ? ' ' : (char) b);
        return sb.toString().stripTrailing();
    }

    @Test
    @DisplayName("a copy does not share storage with what it was copied from")
    void assignmentCopiesRatherThanAliases() {
        Oracle.Result r = run("""
                struct Triple { word a; word b; word c; };
                word main() {
                    struct Triple source;
                    source.a = 1; source.b = 2; source.c = 3;
                    struct Triple copy;
                    copy = source;
                    copy.a = 900; copy.b = 900; copy.c = 900;
                    // The source must be untouched: 1 + 2 + 3.
                    return source.a + source.b + source.c;
                }
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(6, r.a(), () -> "the copy aliased its source\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(), r::describe);
    }

    @Test
    @DisplayName("a member reached through two pointers")
    void chainedArrowAccess() {
        // node->next->value is two loads and two displacements, and getting either
        // offset wrong reads a neighbouring word rather than faulting.
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; };
                word main() {
                    struct Node a;
                    struct Node b;
                    struct Node c;
                    a.value = 1; a.next = &b;
                    b.value = 20; b.next = &c;
                    c.value = 300; c.next = 0;
                    struct Node* p = &a;
                    return p->value + p->next->value + p->next->next->value;
                }
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(321, r.a(), r::describe);
    }

    @Test
    @DisplayName("a struct is packed, and the machine reads a word from an odd address")
    void unalignedMembersReadBack() {
        // { byte; word; word } puts both words at odd offsets. On a machine that
        // required alignment this would fault or silently truncate.
        Oracle.Result r = run("""
                struct Packed { byte flag; word first; word second; };
                word main() {
                    struct Packed p;
                    p.flag = 1;
                    p.first = 40000;
                    p.second = 25535;
                    return p.first + p.second + p.flag;
                }
                """);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), () -> "an odd word address must not fault\n" + r.describe());
        assertEquals(65536 % 65536, r.a(),
                () -> "40000 + 25535 + 1 wraps to 0\n" + r.describe());
    }

    @Test
    @DisplayName("an array of structs indexes by the struct's size")
    void arrayOfStructsStrides() {
        Oracle.Result r = run("""
                struct Cell { word a; word b; word c; };
                struct Cell cells[8];
                word main() {
                    word i = 0;
                    while (i < 8) {
                        cells[i].a = i;
                        cells[i].b = i * 10;
                        cells[i].c = i * 100;
                        i = i + 1;
                    }
                    // If the stride were wrong, later writes would have trampled
                    // earlier ones and this would not add up.
                    word total = 0;
                    i = 0;
                    while (i < 8) {
                        total = total + cells[i].a + cells[i].b + cells[i].c;
                        i = i + 1;
                    }
                    return total;   // 111 * (0+1+...+7)
                }
                """);
        assertTrue(r.ok(), r::describe);
        assertEquals(111 * 28, r.a(), r::describe);
    }

    @Test
    @DisplayName("the worked tree sorts, and its stack comes back balanced")
    void treeExampleRuns() {
        Path source = Path.of("examples/4-data/tree.mona");
        assumeTrue(Files.isReadable(source), "examples/4-data/tree.mona is missing");
        String program;
        try {
            program = Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Oracle.Result r = run(program);
        assertTrue(r.ok(), r::describe);
        assertFalse(r.fault(), r::describe);
        assertEquals("04 11 17 23 29 35 42 56 68 91", screen(r),
                () -> "an in-order walk of a search tree is a sort\n" + r.describe());
        assertEquals(4, r.a(), () -> "the tree's height\n" + r.describe());
        assertEquals(STACK_TOP, r.sp(),
                () -> "recursion through a recursive type must balance\n" + r.describe());
    }
}
