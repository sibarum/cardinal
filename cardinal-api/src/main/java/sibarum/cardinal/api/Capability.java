package sibarum.cardinal.api;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What a {@link Runner} claims it can evaluate itself, and under whose
 * semantics — the declaration half of pushdown.
 *
 * <p>Pushdown is a cost model, not a feature: local evaluation is always
 * available, so sending work to a backend is only ever an optimisation. The
 * dangerous case is a <em>partial</em> pushdown, where some predicates go to
 * the backend and the rest stay local. If the two engines disagree about null
 * handling, collation or integer division, splitting a conjunction changes the
 * answer, and nothing fails — the result is just wrong.
 *
 * <p><b>Nothing verifies this declaration yet.</b> The notary's job is to prove
 * that a given split preserves meaning, or refuse to split; that obligation is
 * not implemented, so a {@code Capability} is currently a claim the runner
 * makes and a planner may read. Treat {@link Semantics#FOREIGN} as a standing
 * reason not to split a conjunction until the proof exists.
 */
public record Capability(Set<String> evaluable, Semantics semantics) {

    /** Whose meaning the backend gives to what it evaluates. */
    public enum Semantics {

        /**
         * The backend agrees with Cardinal — same comparison, same ordering,
         * same arithmetic. A split is safe on semantic grounds.
         */
        CARDINAL,

        /**
         * The backend has its own meaning. Asking for SQL is asking for SQL's
         * semantics, so this is not an error — {@code NULL = NULL} being
         * unknown is what the caller wanted. It does mean a split between this
         * backend and local evaluation is unproven.
         */
        FOREIGN
    }

    public Capability {
        if (semantics == null) {
            throw new IllegalArgumentException("Capability semantics must be non-null");
        }
        evaluable = Set.copyOf(evaluable == null ? Set.of() : evaluable);
    }

    /** Evaluates nothing remotely: everything runs locally. The safe default. */
    public static Capability local() {
        return new Capability(Set.of(), Semantics.CARDINAL);
    }

    /** Evaluates the named symbols, under Cardinal's own semantics. */
    public static Capability agreeing(String... symbols) {
        return new Capability(Set.of(symbols), Semantics.CARDINAL);
    }

    /** Evaluates the named symbols, under the backend's own semantics. */
    public static Capability foreign(String... symbols) {
        return new Capability(Set.of(symbols), Semantics.FOREIGN);
    }

    /** Whether the runner claims it can evaluate {@code symbol} itself. */
    public boolean claims(String symbol) {
        return symbol != null && evaluable.contains(symbol);
    }

    /** This capability plus {@code symbols}, keeping the semantics. */
    public Capability and(String... symbols) {
        Set<String> merged = new LinkedHashSet<>(evaluable);
        merged.addAll(Set.of(symbols));
        return new Capability(merged, semantics);
    }
}
