package dev.madlador.sema;

import dev.madlador.oracle.Mona;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantic analysis. Most of these used to produce no diagnostic at all, because the
 * only symbol table was a map inside the code generator that was filled in while
 * emitting — a name it had not seen simply emitted nothing.
 */
class AnalyzerTest {

    private static void assertReports(String source, String expectedFragment) {
        String diagnostics = Mona.diagnose(source);
        assertTrue(diagnostics.contains(expectedFragment),
                () -> "expected a diagnostic containing '" + expectedFragment
                        + "', got:\n" + diagnostics);
    }

    private static void assertClean(String source) {
        String diagnostics = Mona.diagnose(source);
        assertFalse(diagnostics.contains("error:"),
                () -> "expected no errors, got:\n" + diagnostics);
    }

    @Test
    @DisplayName("an undeclared identifier is an error, not silence")
    void undeclaredIdentifier() {
        assertReports("word main() { return y; }", "undeclared identifier 'y'");
        assertReports("word main() { y = 1; return 0; }", "undeclared identifier 'y'");
    }

    @Test
    @DisplayName("parameters are in scope in the body")
    void parametersResolve() {
        // The old code generator never registered parameters, so `a + b` emitted
        // arithmetic on two values that had never been loaded.
        assertClean("""
                word add(word a, word b) { return a + b; }
                word main() { return add(1, 2); }
                """);
    }

    @Test
    @DisplayName("redeclaring a name in the same scope is an error")
    void redeclaration() {
        assertReports("word main() { word x = 1; word x = 2; return x; }", "already declared");
    }

    @Test
    @DisplayName("a duplicate parameter is an error")
    void duplicateParameter() {
        assertReports("""
                word f(word a, word a) { return a; }
                word main() { return 0; }
                """, "duplicate parameter 'a'");
    }

    @Test
    @DisplayName("a variable cannot read itself in its own initializer")
    void selfReferentialInitializer() {
        assertReports("word main() { word x = x + 1; return x; }", "undeclared identifier 'x'");
    }

    @Test
    @DisplayName("returning a value from void, and returning nothing from non-void")
    void returnTypeChecking() {
        assertReports("void main() { return 5; }", "cannot return a value");
        assertReports("word main() { return; }", "'return' with no value");
    }

    @Test
    @DisplayName("a nested block introduces its own scope")
    void blockScoping() {
        assertClean("word main() { word x = 1; { word x = 2; } return x; }");
        assertReports("word main() { { word x = 1; } return x; }", "undeclared identifier 'x'");
    }

    @Test
    @DisplayName("division or remainder by a literal zero is caught at compile time")
    void divisionByZero() {
        // The machine faults at run time, which is a much worse way to find out.
        assertReports("word main() { return 1 / 0; }", "division by zero");
        assertReports("word main() { return 1 % 0; }", "remainder by zero");
    }

    @Test
    @DisplayName("a literal that fits needs no warning; one that does not gets a precise one")
    void narrowingIsConstantAware() {
        assertFalse(Mona.diagnose("byte b = 7;\nword main() { return b; }").contains("warning"),
                "a literal that already fits in a byte is not a truncation");
        assertReports("byte b = 300;\nword main() { return b; }",
                "the value 300 does not fit in 'byte' and becomes 44");
        // A negative constant is a large 16-bit pattern, but -128..-1 fit a signed byte.
        assertFalse(Mona.diagnose("word main() { sbyte x = -1; sbyte y = -128; return x + y; }")
                        .contains("does not fit"),
                "-1 and -128 fit in an sbyte");
        assertReports("word main() { sbyte x = -129; return x; }", "does not fit in 'sbyte'");
        assertReports("word main() { byte x = -1; return x; }", "does not fit in 'byte'");
        // A compound assignment narrows by definition, as in C, and is not a truncation.
        assertFalse(Mona.diagnose("word main() { byte b = 1; ++b; b += 2; --b; b <<= 1; return b; }")
                        .contains("warning"),
                "++, -- and op= on a byte are silent");
        assertReports("word main() { byte b; word w = 300; b = w; return b; }", "truncates to 8 bits");
    }

    @Test
    @DisplayName("arithmetic that cannot overflow a byte is not a truncation either")
    void narrowingIsRangeAware() {
        // Writing a digit to the display is the case this exists for. Promotion made
        // it a word, but a remainder by ten plus '0' is at most 57, and warning there
        // would only teach that the compiler does not follow the program.
        assertFalse(Mona.diagnose("""
                word main() {
                    byte* screen = 4096;
                    word n = 1234;
                    screen[0] = '0' + n % 10;
                    screen[1] = n & 255;
                    screen[2] = n >> 8;
                    return 0;
                }
                """).contains("warning"),
                "a value that provably fits is not a truncation");
    }

    @Test
    @DisplayName("arithmetic that can overflow a byte still warns")
    void narrowingStillWarnsWhereItMatters() {
        // n / 10 of a word is up to 6553, so the top bits really can be lost. The
        // range analysis has to give up here rather than wave it through.
        assertReports("""
                word main() {
                    byte* screen = 4096;
                    word n = 1234;
                    screen[0] = '0' + n / 10;
                    return 0;
                }
                """, "truncates to 8 bits");
    }

    @Test
    @DisplayName("mixing signed and unsigned operands warns")
    void signednessMixWarns() {
        assertReports("word main() { sword a = 1; word b = 2; return a + b; }",
                "mixing signed and unsigned");
    }

    @Test
    @DisplayName("a literal takes the signedness of what it meets")
    void literalsAreSignednessNeutral() {
        // Every integer literal in this language is a word, so without this the
        // ordinary way of writing signed code mixes signedness on every line and the
        // warning becomes noise. Writing a rotation in fixed point is what surfaced it.
        assertFalse(Mona.diagnose("""
                word main() {
                    sword v = -300;
                    sword shifted = v >> 7;
                    sword scaled = v * 128;
                    if (v < 0) return 1;
                    if (shifted == 0) return 2;
                    return scaled;
                }
                """).contains("warning"),
                "literals should adopt the signedness of the other operand");
    }

    @Test
    @DisplayName("a literal too large to mean itself as signed still warns")
    void largeLiteralAgainstSignedStillWarns() {
        // 40000 read as an sword is -25536, which is a different number and worth
        // stopping for. Writing -25536 says the same thing on purpose.
        assertReports("word main() { sword v = 1; if (v < 40000) return 1; return 0; }",
                "mixing signed and unsigned");
    }

    @Test
    @DisplayName("negating a literal is how a negative number is written")
    void negatingLiteralsIsSilent() {
        assertFalse(Mona.diagnose("sword g = -5;\nword main() { return 0; }").contains("warning"),
                "-5 is a way of writing a number, not an operation on an unsigned value");
        // A computed value is a different matter: -n really does wrap.
        assertReports("word main() { word n = 5; word m = -n; return m; }",
                "negating an unsigned value wraps around");
    }

    @Test
    @DisplayName("signed division, remainder and shift are accepted")
    void signedOperationsAccepted() {
        // These have no instruction on this machine and become calls to runtime
        // helpers; until M7 they were an explicit error rather than a wrong answer.
        assertClean("sword main() { sword a = 10; sword b = 3; return a / b; }");
        assertClean("sword main() { sword a = 10; sword b = 3; return a % b; }");
        assertClean("sword main() { sword a = 10; return a >> 2; }");
    }

    @Test
    @DisplayName("a missing return in a non-void function warns")
    void missingReturnWarns() {
        assertReports("word main() { word x = 1; }", "may finish without returning a value");
    }

    @Test
    @DisplayName("a program with no main is rejected")
    void entryPointRequired() {
        assertReports("word start() { return 1; }", "no function named 'main'");
    }

    @Test
    @DisplayName("calls are checked for arity and for what is being called")
    void callChecking() {
        assertClean("word f(word a) { return a; }\nword main() { return f(1); }");
        assertReports("word f(word a) { return a; }\nword main() { return f(); }",
                "takes 1 argument(s), but 0 were given");
        assertReports("word f(word a) { return a; }\nword main() { return f(1, 2); }",
                "takes 1 argument(s), but 2 were given");
        assertReports("word main() { return nope(); }", "undeclared function 'nope'");
        assertReports("word main() { word x = 1; return x(); }", "is a variable, not a function");
    }

    @Test
    @DisplayName("a function may call one defined later in the file")
    void forwardReference() {
        // Requires declaring every top-level name before analysing any body.
        assertClean("""
                word main() { return later(); }
                word later() { return 1; }
                """);
    }

    @Test
    @DisplayName("main may not take parameters")
    void mainTakesNoParameters() {
        assertReports("word main(word a) { return a; }", "'main' may not take parameters");
    }

    @Test
    @DisplayName("a global initializer must be a constant expression")
    void globalInitializerMustFold() {
        assertClean("word g = 2 * 21;\nword main() { return g; }");
        assertReports("word a = 1;\nword b = a + 1;\nword main() { return b; }",
                "must be a constant expression");
    }

    @Test
    @DisplayName("an array is initialized with a list, not a single value")
    void arrayInitializerNeedsAList() {
        // A lone scalar used to be accepted and dropped, which read back as zeroes
        // with nothing said. Now there is a list syntax, and the scalar says to use it.
        assertReports("word main() { word a[3] = 7; return a[0]; }",
                "an array is initialized with a list");
        assertReports("word g[2] = 1;\nword main() { return g[0]; }",
                "an array is initialized with a list");
        assertClean("word main() { word x = 2 + 3; word a[3] = {x}; return a[0]; }");
    }

    @Test
    @DisplayName("writing a read-only port is an error, not a runtime fault")
    void readOnlyPortsRejected() {
        // Writing one of these raises an exception the machine cannot deliver: there
        // is no vector, the CPU latches into fault mode, and every later step throws.
        for (int port : new int[]{1, 4, 5, 6, 10}) {
            assertReports("word main() { __out(" + port + ", 0); return 0; }",
                    "which is read-only");
        }
        // Naming the register is most of the value of the message.
        assertReports("word main() { __out(4, 0); return 0; }", "port 4 is TMRCOUNTER");
        // Reading them is exactly what they are for.
        assertClean("word main() { return __in(4) + __in(5) + __in(1); }");
        // And the writable ones stay writable.
        assertClean("word main() { __out(0, 2); __out(3, 1000); __out(7, 1); "
                + "__out(8, 0); __out(9, 255); __out(2, 4); return 0; }");
    }

    @Test
    @DisplayName("a port the machine does not have is an error either way")
    void portsAboveTenRejected() {
        assertReports("word main() { __out(11, 0); return 0; }", "there is no port 11");
        assertReports("word main() { return __in(42); }", "there is no port 42");
        assertClean("word main() { return __in(10); }");
    }

    @Test
    @DisplayName("a video mode above 4 warns, because it poisons the __v* builtins")
    void impossibleVideoModeWarns() {
        // The card ignores it, but IORegMap stores it anyway, so __in(7) reports a
        // mode the card is not in -- and all four __v* helpers branch on that.
        assertReports("word main() { __out(7, 9); return 0; }", "video mode 9 does not exist");
        for (int mode = 0; mode <= 4; mode++) {
            assertClean("word main() { __out(7, " + mode + "); return 0; }");
        }
    }

    @Test
    @DisplayName("only literal ports are judged")
    void computedPortsAreLeftAlone() {
        // A loop over the video registers is legitimate, and a false positive on one
        // would be worse than missing the literal case.
        assertClean("""
                word main() {
                    for (word p = 7; p < 10; p += 1) __out(p, 0);
                    word q = 4;
                    __out(q, 1);
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("two top-level items may not share a name")
    void topLevelRedeclaration() {
        assertReports("""
                word f() { return 1; }
                word f() { return 2; }
                word main() { return 0; }
                """, "is already defined");
    }

    @Test
    @DisplayName("break and continue outside a loop are errors")
    void loopControlOutsideLoop() {
        assertReports("word main() { break; }", "'break' outside of a loop");
        assertReports("word main() { continue; }", "'continue' outside of a loop");
        assertClean("word main() { while (1) { break; } return 0; }");
        assertClean("word main() { for (word i = 0; i < 1; i += 1) { continue; } return 0; }");
    }

    @Test
    @DisplayName("a for-loop initializer declares into the loop's own scope")
    void forLoopScope() {
        assertClean("word main() { for (word i = 0; i < 1; i += 1) { } return 0; }");
        // i must not leak into the enclosing block.
        assertReports("word main() { for (word i = 0; i < 1; i += 1) { } return i; }",
                "undeclared identifier 'i'");
        // Two loops may each declare i without colliding.
        assertClean("""
                word main() {
                    for (word i = 0; i < 1; i += 1) { }
                    for (word i = 0; i < 1; i += 1) { }
                    return 0;
                }
                """);
    }

    @Test
    @DisplayName("a variable may not be used as a function, or vice versa")
    void kindConfusion() {
        // A function's name is its address now, as in C, so `return main;` is a pointer
        // turned into a word. Calling something that is not a function is still wrong.
        assertReports("word main() { word x = 1; return x(2); }", "'x' is a variable, not a function");
        assertReports("word main() { main = 1; return 0; }", "cannot assign to function");
    }

    @Test
    @DisplayName("a prototype and its definition must agree")
    void prototypeConflicts() {
        assertReports("""
                word f(word a);
                byte f(word a) { return 1; }
                word main() { return 0; }
                """, "conflicting types for 'f'");
        assertReports("""
                word f(word a);
                word f(word a, word b) { return a; }
                word main() { return 0; }
                """, "conflicting types for 'f'");
        assertReports("""
                word f(byte* p) { return 0; }
                word f(word* p);
                word main() { return 0; }
                """, "it was first declared as word f(byte*)");
    }

    @Test
    @DisplayName("prototypes may repeat, come before or after the definition, and omit names")
    void prototypesThatAgree() {
        assertClean("""
                word f(word);
                word f(word a);
                word f(word b) { return b; }
                word f(word c);
                word main(void) { return f(1); }
                """);
    }

    @Test
    @DisplayName("using a function that was only ever prototyped is an error")
    void prototypeWithoutDefinition() {
        assertReports("""
                word f(word);
                word main() { return f(1); }
                """, "'f' is declared but never defined");
        assertReports("""
                void handler();
                word main() { word a = &handler; return 0; }
                """, "'handler' is declared but never defined");
        // Declared and never used costs nothing, as in C.
        assertClean("""
                word f(word);
                word main() { return 0; }
                """);
    }

    @Test
    @DisplayName("a definition still needs every parameter named")
    void definitionNeedsParameterNames() {
        assertReports("""
                word f(word) { return 0; }
                word main() { return f(1); }
                """, "a parameter needs a name");
    }
}
