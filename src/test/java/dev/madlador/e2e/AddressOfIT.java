package dev.madlador.e2e;

import dev.madlador.diag.DiagnosticReporter;
import dev.madlador.diag.SourceFile;
import dev.madlador.driver.Compiler;
import dev.madlador.driver.Options;
import dev.madlador.oracle.Oracle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Taking the address of things, and storing through the addresses that come back.
 *
 * <p>Two silent wrong-code bugs lived here, and both are the kind that a test reading
 * assembly would not have caught — the code was plausible, it just did something else.
 *
 * <p>The first: {@code &} of a struct member returned <b>zero</b>. {@code Lowering}
 * had cases for {@code &x}, {@code &*p} and {@code &a[i]}, none for {@code &s.field},
 * and a fallback that returned {@code Imm(0)} rather than failing. So {@code
 * &p->next} — the pointer-to-the-link idiom that makes a list insert need no special
 * case for the head — silently became a null pointer, at every optimization level.
 *
 * <p>The second: storing through a spilled pointer loaded it into {@code B}, which is
 * a colour, so it overwrote whatever the allocator had put there. {@code n->value =
 * count;} in a loop incremented the pointer instead of the counter. Only at {@code
 * -O1}, because only then is anything in a register to lose.
 *
 * <p>Everything here runs at both levels: the first bug was in lowering and appeared
 * at both, the second was in selection and appeared at one, and a test that checked
 * only the optimized build would have missed the first while a test that checked only
 * {@code -O0} would have missed the second.
 */
@DisplayName("address-of")
class AddressOfIT {

    @BeforeAll
    static void requireOracle() {
        assumeTrue(Oracle.isAvailable(), Oracle.unavailableReason());
    }

    private static Oracle.Result run(String source, int optLevel) {
        SourceFile file = new SourceFile("test.mona", source);
        DiagnosticReporter reporter = new DiagnosticReporter(file);
        Compiler.Result result =
                new Compiler(file, reporter).compile(Options.Stage.ASM, true, optLevel, 0);
        if (reporter.hasErrors()) {
            throw new AssertionError("compilation failed:\n" + reporter.render());
        }
        return Oracle.get().run(result.assembly(), 500_000);
    }

    @ParameterizedTest(name = "-O{0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("every kind of place has an address, members included")
    void everyPlaceHasAnAddress(int optLevel) {
        // Each bit is one form. Members used to contribute nothing at all, so this
        // returned 9 -- only the two non-member forms.
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; };
                struct Node n;
                struct Node arr[2];

                word main() {
                    struct Node* p = &n;
                    word r = 0;
                    if (&n != 0)            r = r + 1;    // a global struct
                    if (&n.next != 0)       r = r + 2;    // its field, directly
                    if (&p->next != 0)      r = r + 4;    // its field, through a pointer
                    if (&arr[1] != 0)       r = r + 8;    // an array element
                    if (&arr[1].next != 0)  r = r + 16;   // a field of an element
                    if (&n.value != 0)      r = r + 32;   // the first field, directly
                    if (&p->value != 0)     r = r + 64;   // the first field, by pointer
                    return r;
                }
                """, optLevel);

        assertTrue(r.ok(), r::describe);
        assertEquals(127, r.a(), () -> "a member's address is missing\n" + r.describe());
    }

    @ParameterizedTest(name = "-O{0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("a member's address is the struct's plus the offset")
    void memberAddressIsOffsetFromTheStruct(int optLevel) {
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; };
                struct Node n;

                word main() {
                    struct Node* p = &n;
                    word first  = &p->value;
                    word second = &p->next;
                    word base   = &n;
                    // The first field is the struct itself; the second is two on.
                    return (first - base) * 10 + (second - base);
                }
                """, optLevel);

        assertTrue(r.ok(), r::describe);
        assertEquals(2, r.a(), r::describe);
    }

    @ParameterizedTest(name = "-O{0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("a list insert threaded through a pointer to the link")
    void insertThroughAPointerToTheLink(int optLevel) {
        // The idiom &(*link)->next exists so that appending needs no special case for
        // the empty list: link points at whatever has to change, head included. With
        // that address coming back as zero the loop never advanced and the list was
        // built on top of address zero.
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; };
                struct Node pool[3];

                void append(struct Node** link, struct Node* node) {
                    while (*link != 0) {
                        link = &(*link)->next;
                    }
                    *link = node;
                    node->next = 0;
                }

                word main() {
                    struct Node* head = 0;
                    for (word i = 0; i < 3; i = i + 1) {
                        pool[i].value = i + 1;
                        append(&head, &pool[i]);
                    }
                    // Appended, so the order is the order they went in: 1, 2, 3.
                    word seen = 0;
                    struct Node* walk = head;
                    while (walk != 0) {
                        seen = seen * 10 + walk->value;
                        walk = walk->next;
                    }
                    return seen;                       // 123
                }
                """, optLevel);

        assertTrue(r.ok(), r::describe);
        assertEquals(123, r.a(), r::describe);
    }

    @ParameterizedTest(name = "-O{0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("storing through a spilled pointer keeps the other values")
    void storeThroughSpilledPointer(int optLevel) {
        // The pointer is spilled because next, end and the counter are all live
        // across the store. Loading it into B destroyed the counter, so this
        // returned the pointer instead of 4.
        Oracle.Result r = run("""
                struct Node { word value; struct Node* next; word spare; };
                struct Node pool[4];

                word main() {
                    struct Node* next = &pool[0];
                    struct Node* end  = &pool[4];
                    word fitted = 0;
                    while (next != end) {
                        struct Node* n = next;
                        next = next + 1;
                        n->value = fitted;
                        fitted = fitted + 1;
                    }
                    // And every stamp survived, which says nothing overlapped.
                    word sum = 0;
                    for (word i = 0; i < 4; i = i + 1) sum = sum + pool[i].value;
                    return fitted * 10 + sum;          // 4 * 10 + (0+1+2+3)
                }
                """, optLevel);

        assertTrue(r.ok(), r::describe);
        assertEquals(46, r.a(), r::describe);
    }
}
