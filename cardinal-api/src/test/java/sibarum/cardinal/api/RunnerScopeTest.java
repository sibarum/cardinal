package sibarum.cardinal.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A runner puts symbols in scope, and a context's scope is its own.
 *
 * <p>The second of those is the load-bearing one. Upstream the module universe
 * is a process-global registry, so two embeddings with different vocabularies
 * would overwrite one another. Here it belongs to the context, which is what
 * lets a filesystem runner and a SQL runner be alive in one JVM at once — the
 * arrangement the whole runner design assumes.
 */
class RunnerScopeTest {

    private static final Source GEOM = Source.of("geom.cdl", """
            module geom.shapes
            exports @.{Vec}
            struct Vec(x:Int, y:Int)
            """);

    private static final Source CATALOG = Source.of("catalog.cdl", """
            module sql.catalog
            exports @.{Row}
            struct Row(id:Int, size:Int)
            """);

    private static final String USES_GEOM = """
            module app.main
            requires geom.shapes.{Vec}
            function f(v:[Vec(x, y)]):Int -> x + y
            f(Vec(3, 4))
            """;

    private static final String USES_CATALOG = """
            module app.main
            requires sql.catalog.{Row}
            function g(r:[Row(id, size)]):Int -> id + size
            g(Row(1, 2))
            """;

    // --- a runner puts symbols in scope -------------------------------------

    @Test
    void aProgramRequiringARunnersModule_compiles() {
        CardinalContext cardinal = Cardinal.context()
                .runner(new StubRunner("geometry", GEOM))
                .build();

        assertTrue(cardinal.compile(Source.of("q.cdl", USES_GEOM)).isChecked());
        assertEquals(List.of("geom.shapes"), List.copyOf(cardinal.universe().keySet()));
    }

    @Test
    void theSameProgramWithoutTheRunner_isRejected() {
        Compilation.Rejected no = assertInstanceOf(Compilation.Rejected.class,
                Cardinal.bare().compile(Source.of("q.cdl", USES_GEOM)));

        assertTrue(no.diagnostics().get(0).message().contains("geom.shapes"),
                () -> no.diagnostics().get(0).render());
    }

    @Test
    void anUnusedRunnerCostsAProgramNothing() {
        CardinalContext cardinal = Cardinal.context()
                .runner(new StubRunner("geometry", GEOM))
                .build();

        Compilation.Checked ok = (Compilation.Checked) cardinal.compile(Source.of("q.cdl", """
                module demo
                function add(a:Int, b:Int):Int -> a + b
                add(2, 3)
                """));

        // The runner's Vec is in the universe but was never required, so it is
        // not in the program — it cannot shadow anything or create ambiguity.
        assertFalse(ok.ir().statements().toString().contains("Vec"),
                "an unrequired module must not be injected");
    }

    @Test
    void aModuleCanBeContributedWithoutARunnerBehindIt() {
        CardinalContext cardinal = Cardinal.context().module(GEOM).build();

        assertTrue(cardinal.compile(Source.of("q.cdl", USES_GEOM)).isChecked());
        assertTrue(cardinal.runners().isEmpty());
    }

    // --- two contexts, one JVM ----------------------------------------------

    @Test
    void twoContextsWithDifferentRunners_doNotSeeEachOthersModules() {
        CardinalContext filesystem = Cardinal.context()
                .runner(new StubRunner("filesystem", GEOM))
                .build();
        CardinalContext sql = Cardinal.context()
                .runner(new StubRunner("postgres", CATALOG))
                .build();

        // Each context compiles its own program...
        assertTrue(filesystem.compile(Source.of("q.cdl", USES_GEOM)).isChecked());
        assertTrue(sql.compile(Source.of("q.cdl", USES_CATALOG)).isChecked());

        // ...and neither can compile the other's, which is what "the scope is
        // the context's" actually means. A global registry would let both.
        assertInstanceOf(Compilation.Rejected.class,
                filesystem.compile(Source.of("q.cdl", USES_CATALOG)));
        assertInstanceOf(Compilation.Rejected.class,
                sql.compile(Source.of("q.cdl", USES_GEOM)));
    }

    @Test
    void buildingASecondContextDoesNotDisturbTheFirst() {
        CardinalContext first = Cardinal.context()
                .runner(new StubRunner("filesystem", GEOM))
                .build();

        assertTrue(first.compile(Source.of("q.cdl", USES_GEOM)).isChecked());
        Cardinal.context().runner(new StubRunner("postgres", CATALOG)).build();
        assertTrue(first.compile(Source.of("q.cdl", USES_GEOM)).isChecked(),
                "a later context must not disturb an earlier one");
    }

    @Test
    void oneContextServesConcurrentCompiles() throws Exception {
        CardinalContext cardinal = Cardinal.context()
                .runner(new StubRunner("geometry", GEOM))
                .build();
        List<Callable<Boolean>> work = IntStream.range(0, 32)
                .mapToObj(i -> (Callable<Boolean>) () ->
                        cardinal.compile(Source.of("q" + i + ".cdl", USES_GEOM)).isChecked())
                .toList();

        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Boolean> f : pool.invokeAll(work)) {
                assertTrue(f.get(), "every concurrent compile should check");
            }
        }
    }

    // --- capability declarations --------------------------------------------

    @Test
    void aRunnersDeclaredCapabilityIsCarriedAndAddressable() {
        Runner geo = new StubRunner("geospatial", List.of(GEOM),
                Capability.foreign("geom.shapes/haversine"));
        CardinalContext cardinal = Cardinal.context().runner(geo).build();

        assertEquals("geospatial",
                cardinal.runnerFor("geom.shapes/haversine").orElseThrow().name());
        assertEquals(Capability.Semantics.FOREIGN, geo.capability().semantics());
        assertFalse(cardinal.runnerFor("geom.shapes/unclaimed").isPresent());
    }
}
