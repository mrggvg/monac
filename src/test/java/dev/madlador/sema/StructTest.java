package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Struct declarations, layout, and the rules around them.
 *
 * <p>Whether the generated code is <em>right</em> is covered by running programs in
 * {@code StructIT}; these are about what the compiler accepts, what it refuses, and
 * whether it says something useful when it refuses.
 */
class StructTest {

    private static void assertReports(String source, String expected) {
        String diagnostics = Mona.diagnose(source);
        assertTrue(diagnostics.contains(expected),
                () -> "expected a diagnostic containing \"" + expected + "\", got:\n" + diagnostics);
    }

    private static void assertClean(String source) {
        String diagnostics = Mona.diagnose(source);
        assertTrue(diagnostics.isBlank(),
                () -> "expected no diagnostics, got:\n" + diagnostics);
    }

    @Test
    @DisplayName("a struct may refer to itself through a pointer")
    void selfReferenceThroughPointer() {
        // The shape every linked structure has, and the reason the struct table is
        // built in two passes.
        assertClean("""
                struct Node {
                    word value;
                    struct Node* next;
                };
                word main() {
                    struct Node n;
                    n.value = 1;
                    n.next = 0;
                    return n.value;
                }
                """);
    }

    @Test
    @DisplayName("a struct may not contain itself by value")
    void selfReferenceByValueIsInfinite() {
        assertReports("""
                struct Bad { struct Bad inner; };
                word main() { return 0; }
                """, "contains itself, so it has no size");
    }

    @Test
    @DisplayName("two structs may refer to each other, in either order")
    void mutualReferenceAndForwardUse() {
        assertClean("""
                struct A { struct B* b; word x; };
                struct B { struct A* a; word y; };
                word main() {
                    struct A first;
                    struct B second;
                    first.b = &second;
                    second.a = &first;
                    first.x = 1;
                    second.y = 2;
                    return first.b->y;
                }
                """);
    }

    @Test
    @DisplayName("members are packed, because the machine needs no alignment")
    void thereIsNoPadding() {
        // A word can be loaded from an odd address here, so a byte followed by a
        // word really is three bytes. sizeof is the observable consequence.
        String assembly = Mona.compile("""
                struct Packed { byte flag; word value; };
                word main() { return sizeof(struct Packed); }
                """).assembly();
        assertTrue(assembly.contains("MOV   A, 3"),
                () -> "expected a size of 3, not a padded 4:\n" + assembly);
    }

    @Test
    @DisplayName("'.' on a pointer and '->' on a struct each say what to use instead")
    void wrongAccessOperatorIsDiagnosed() {
        assertReports("""
                struct Point { word x; word y; };
                word main() {
                    struct Point p;
                    struct Point* q = &p;
                    return q.x;
                }
                """, "it is a pointer to one; use '->'");

        assertReports("""
                struct Point { word x; word y; };
                word main() {
                    struct Point p;
                    return p->x;
                }
                """, "is a struct, not a pointer; use '.'");
    }

    @Test
    @DisplayName("an unknown member lists the ones that exist")
    void unknownMemberSuggests() {
        assertReports("""
                struct Point { word x; word y; };
                word main() {
                    struct Point p;
                    return p.z;
                }
                """, "it has 'x' and 'y'");
    }

    @Test
    @DisplayName("a struct cannot be passed or returned by value")
    void byValueIsRefused() {
        assertReports("""
                struct Point { word x; word y; };
                word take(struct Point p) { return p.x; }
                word main() { return 0; }
                """, "a parameter cannot be a struct by value");

        assertReports("""
                struct Point { word x; word y; };
                struct Point make() { struct Point p; p.x = 1; p.y = 2; return p; }
                word main() { return 0; }
                """, "a function cannot return a struct by value");
    }

    @Test
    @DisplayName("a struct is assignable only from the same struct")
    void assignmentRequiresTheSameStruct() {
        assertClean("""
                struct Point { word x; word y; };
                word main() {
                    struct Point a;
                    struct Point b;
                    a.x = 1;
                    a.y = 2;
                    b = a;
                    return b.x + b.y;
                }
                """);

        // Matching members are not enough: identity is by declaration, as in C.
        assertReports("""
                struct Point { word x; word y; };
                struct Pair { word x; word y; };
                word main() {
                    struct Point a;
                    struct Pair b;
                    b = a;
                    return 0;
                }
                """, "cannot assign 'struct Point' to 'struct Pair'");
    }

    @Test
    @DisplayName("a struct declared inside a function says where it belongs")
    void structsAreFileScope() {
        assertReports("""
                word main() {
                    struct Local { word x; };
                    return 0;
                }
                """, "must be declared at file scope");
    }

    @Test
    @DisplayName("a struct too large to address is caught at its declaration")
    void oversizedStructIsRefused() {
        // [B+n] encodes n as a signed byte, so a member past 127 could not be named.
        // Saying so at the declaration beats a displacement overflow much later.
        assertReports("""
                struct Huge { word a[70]; };
                word main() { return 0; }
                """, "at most 127 can be addressed");
    }

    @Test
    @DisplayName("an unknown struct name is reported once, where it is used")
    void unknownStructName() {
        assertReports("""
                word main() {
                    struct Missing m;
                    return 0;
                }
                """, "unknown type 'struct Missing'");
    }

    @Test
    @DisplayName("a duplicate declaration and a duplicate member are both errors")
    void duplicatesAreRefused() {
        assertReports("""
                struct P { word x; };
                struct P { word y; };
                word main() { return 0; }
                """, "'struct P' is already declared");

        assertReports("""
                struct P { word x; word x; };
                word main() { return 0; }
                """, "duplicate member 'x'");
    }

    @Test
    @DisplayName("a struct member costs no more than a plain local")
    void memberAccessIsOneInstruction() {
        // The point of putting a byte displacement on a frame slot: [D+k] already
        // takes one, so a member needs no address computed into a register.
        String assembly = Mona.compile("""
                struct Point { word x; word y; };
                word main() {
                    struct Point p;
                    p.x = 10;
                    p.y = 20;
                    return p.x + p.y;
                }
                """).assembly();
        assertTrue(assembly.contains("MOV   [SP+1], 10") && assembly.contains("MOV   [SP+3], 20"),
                () -> "members should be written straight to the frame:\n" + assembly);
        assertFalse(assembly.contains("ADD   A, 2"),
                () -> "no offset should be computed at run time:\n" + assembly);
    }
}
