package sibarum.cardinal.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The context surface: build one, put source through it, read the outcome.
 */
class CardinalContextTest {

    private static Compilation.Checked check(Compilation c) {
        if (c instanceof Compilation.Rejected r) {
            throw new AssertionError("expected a checked compilation; got "
                    + r.diagnostics().stream().map(Diagnostic::render).toList());
        }
        return (Compilation.Checked) c;
    }

    private static Compilation.Rejected reject(Compilation c) {
        return assertInstanceOf(Compilation.Rejected.class, c,
                () -> "expected a rejection; got a checked compilation");
    }

    // --- the happy path -----------------------------------------------------

    @Test
    void bareContext_compilesASelfContainedProgram() {
        Compilation.Checked ok = check(Cardinal.bare().compile(Source.of("t.cdl", """
                module demo
                function add(a:Int, b:Int):Int -> a + b
                add(2, 3)
                """)));

        assertEquals("demo", ok.entryModule());
        assertEquals("demo", ok.ir().name());
        assertNotNull(ok.compiled().dispatch());
    }

    @Test
    void checkedProgram_carriesTheNotarysEvidence() {
        Compilation.Checked ok = check(Cardinal.bare().compile(Source.of("t.cdl", """
                module demo
                function square(x:Int):[Int:@>=0] -> x * x
                square(4)
                """)));

        Evidence evidence = ok.evidence();
        assertTrue(evidence.receipts().isPresent(), "a receipt graph should have been drafted");
        assertTrue(evidence.allDischarged(), () -> "outstanding: " + evidence.outstanding());
        assertTrue(evidence.discharges().stream().anyMatch(d -> d.functionName().equals("square")),
                () -> "square's obligation should be recorded; got " + evidence.discharges());
        assertTrue(evidence.render().contains("square"), evidence::render);
    }

    @Test
    void aContextIsReusable_andRepeatedCompilesAgree() {
        CardinalContext cardinal = Cardinal.bare();
        Source source = Source.of("t.cdl", """
                module demo
                function square(x:Int):[Int:@>=0] -> x * x
                square(4)
                """);

        Compilation.Checked first = check(cardinal.compile(source));
        Compilation.Checked second = check(cardinal.compile(source));

        assertEquals(first.entryModule(), second.entryModule());
        assertEquals(first.evidence().discharges().size(), second.evidence().discharges().size());
    }

    // --- rejection ----------------------------------------------------------

    @Test
    void parseFailure_isADiagnosticWithAPosition_notAnException() {
        Compilation.Rejected no = reject(Cardinal.bare().compile(
                Source.of("broken.cdl", "module demo\nfunction (:")));

        assertEquals(1, no.diagnostics().size());
        Diagnostic d = no.diagnostics().get(0);
        assertEquals(Diagnostic.Phase.PARSE, d.phase());
        assertTrue(d.origin().isPresent(), () -> "expected a position; got " + d.render());
        assertTrue(d.render().startsWith("parse: "), d::render);
    }

    @Test
    void aDiagnosticRendersItsPositionOnce_notTwice() {
        // The compiler's own exceptions bracket the origin into the front of
        // their message; the position is carried structurally here, so the
        // rendered line must not say it twice.
        Diagnostic d = reject(Cardinal.bare().compile(Source.of("q.cdl", """
                module app.main
                requires geom.shapes.{Vec}
                1
                """))).diagnostics().get(0);

        assertEquals(Diagnostic.Phase.LINK, d.phase());
        String where = d.origin().orElseThrow().toString();
        assertEquals(1, countOf(d.render(), where),
                () -> "position should appear once; got: " + d.render());
        assertFalse(d.message().startsWith("["), () -> "message: " + d.message());
    }

    @Test
    void diagnosticsAreAscii_soTheySurviveAnyLog() {
        String rendered = reject(Cardinal.bare().compile(Source.of("t.cdl", """
                module demo
                function f(x:Int):[Int:@>0] -> x
                f(1)
                """))).diagnostics().get(0).render();

        assertTrue(rendered.chars().allMatch(c -> c < 128),
                () -> "non-ASCII in a diagnostic: " + rendered);
    }

    private static int countOf(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    @Test
    void undischargeableReturnRefinement_isRejectedByTheReturnGate() {
        Compilation.Rejected no = reject(Cardinal.bare().compile(Source.of("t.cdl", """
                module demo
                function f(x:Int):[Int:@>0] -> x
                f(1)
                """)));

        assertEquals(Diagnostic.Phase.RETURN, no.diagnostics().get(0).phase());
        assertTrue(no.diagnostics().get(0).message().contains("f"),
                () -> no.diagnostics().get(0).render());
    }

    @Test
    void orThrow_carriesTheDiagnostics() {
        Compilation.RejectedException thrown = assertThrows(
                Compilation.RejectedException.class,
                () -> Cardinal.bare().compile(Source.of("broken.cdl", "module demo\nfunction (:"))
                        .orThrow());

        assertEquals(1, thrown.diagnostics().size());
        assertEquals(Diagnostic.Phase.PARSE, thrown.diagnostics().get(0).phase());
    }

    @Test
    void noSources_isRejectedRatherThanThrowing() {
        assertEquals(Diagnostic.Phase.PARSE,
                reject(Cardinal.bare().compile(List.of())).diagnostics().get(0).phase());
    }

    // --- entry modules ------------------------------------------------------

    @Test
    void severalSources_compileAsOneProgramWithANamedEntry() {
        Compilation.Checked ok = check(Cardinal.bare().compile(List.of(
                Source.of("app.cdl", """
                        module app.main
                        requires geom.shapes.{Vec}
                        function f(v:[Vec(x, y)]):Int -> x + y
                        f(Vec(3, 4))
                        """),
                Source.of("geom.cdl", """
                        module geom.shapes
                        exports @.{Vec}
                        struct Vec(x:Int, y:Int)
                        """)), "app.main"));

        assertEquals("app.main", ok.entryModule());
    }

    @Test
    void severalSources_inferTheEntryFromTheSoleMain() {
        Compilation.Checked ok = check(Cardinal.bare().compile(List.of(
                Source.of("geom.cdl", """
                        module geom.shapes
                        exports @.{Vec}
                        struct Vec(x:Int, y:Int)
                        """),
                Source.of("app.cdl", """
                        module app.main
                        requires geom.shapes.{Vec}
                        function f(v:[Vec(x, y)]):Int -> x + y
                        f(Vec(3, 4))
                        """))));

        assertEquals("app.main", ok.entryModule());
    }

    @Test
    void unknownEntryModule_isRejectedAndNamesWhatWasAvailable() {
        Compilation.Rejected no = reject(Cardinal.bare().compile(
                List.of(Source.of("t.cdl", "module demo\n1")), "nope"));

        assertEquals(Diagnostic.Phase.LINK, no.diagnostics().get(0).phase());
        assertTrue(no.diagnostics().get(0).message().contains("demo"),
                () -> no.diagnostics().get(0).render());
    }

    // --- the S-expression front end ----------------------------------------

    @Test
    void theSexprFrontEndIsReachableThroughTheSameContext() {
        Compilation.Checked ok = check(Cardinal.bare().compile(Source.sexpr("t.sexpr",
                "(module square ((defn square ((n Int)) Int (* n n))) (call square 5))")));

        assertEquals("square", ok.entryModule());
    }

    // --- setup errors -------------------------------------------------------

    @Test
    void aRunnerContributingUnparseableSource_failsTheBuild_notTheFirstCompile() {
        CardinalContext.SetupException e = assertThrows(CardinalContext.SetupException.class,
                () -> Cardinal.context()
                        .runner(new StubRunner("broken", Source.of("bad.cdl", "module x\nfunction (:")))
                        .build());

        assertTrue(e.getMessage().contains("broken"), e::getMessage);
        assertTrue(e.getMessage().contains("does not parse"), e::getMessage);
    }

    @Test
    void twoRunnersClaimingOneModuleName_failTheBuild() {
        Source vec = Source.of("geom.cdl", """
                module geom.shapes
                exports @.{Vec}
                struct Vec(x:Int, y:Int)
                """);

        CardinalContext.SetupException e = assertThrows(CardinalContext.SetupException.class,
                () -> Cardinal.context()
                        .runner(new StubRunner("first", vec))
                        .runner(new StubRunner("second", vec))
                        .build());

        assertTrue(e.getMessage().contains("geom.shapes"), e::getMessage);
        assertTrue(e.getMessage().contains("second"), e::getMessage);
    }

    @Test
    void aBareContextHasNoRunnersAndAnEmptyUniverse() {
        CardinalContext bare = Cardinal.bare();
        assertTrue(bare.runners().isEmpty());
        assertTrue(bare.universe().isEmpty());
        assertFalse(bare.runnerFor("anything").isPresent());
    }
}
