package sibarum.cardinal.api;

import java.util.List;
import java.util.Set;

/**
 * A runner is what makes a query mean something. It supplies the data, it
 * supplies the scope, and it owns the semantics.
 *
 * <p>The part a runner plays at <em>compile</em> time — which is all a
 * {@link CardinalContext} does — is to put symbols in scope. It contributes
 * modules of ordinary Cardinal declarations, and a program that
 * {@code requires} one of them type-checks against it and has its claims
 * discharged against it like any other code. A geospatial runner declaring
 * {@code haversine} is what lets a filter call {@code haversine}; whether that
 * call is later pushed to a backend with {@code ST_Distance} or evaluated over
 * streamed rows is the runner's plan, not the front end's business.
 *
 * <p>Runners are registered on a context and read once, when the context is
 * built. A runner whose modules do not parse fails the build rather than the
 * first compile.
 *
 * <p>Implementations should be immutable, or at least safe to read from several
 * threads: one context serves many compiles.
 */
public interface Runner {

    /**
     * A short, stable name — used in diagnostics when this runner's
     * contribution is at fault. Not a module name.
     */
    String name();

    /**
     * The modules this runner puts in scope, as Cardinal source. Each is parsed
     * when the context is built and offered to the linker; only those a program
     * actually {@code requires} are injected, so an unused runner costs a
     * program nothing and cannot shadow its names.
     */
    default List<Source> modules() {
        return List.of();
    }

    /**
     * Qualified names this runner's modules provide that an
     * {@code assign proof f:Algebraic} body may call without being themselves
     * proof-claimed — a primitive is a leaf, with no body of its own to verify.
     */
    default Set<String> algebraicPrimitives() {
        return Set.of();
    }

    /**
     * What this runner claims it can evaluate itself, and under whose
     * semantics. Read {@link Capability} before relying on it: the declaration
     * is carried, not yet checked.
     */
    default Capability capability() {
        return Capability.local();
    }
}
