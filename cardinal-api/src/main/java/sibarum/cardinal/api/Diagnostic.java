package sibarum.cardinal.api;

import sibarum.cardinal.core.Origin;

import java.util.Optional;

/**
 * One reason a compilation was rejected: which stage refused, what it said, and
 * where in the source it was looking.
 *
 * <p>The phase is carried separately rather than glued onto the front of the
 * message, so a caller can group, filter or colour by it without parsing
 * prose.
 */
public record Diagnostic(Phase phase, String message, Optional<Origin> origin) {

    /** The stage that refused. Ordered as the pipeline runs. */
    public enum Phase {

        /** Reading the source text. */
        PARSE,

        /** Resolving {@code requires} and combining modules. */
        LINK,

        /** Alias resolution, trait lowering, method and operator routing. */
        RESOLVE,

        /** Type checking, inference and coercion. */
        TYPE,

        /** Arguments provably violate every overload's parameter refinements. */
        CALL,

        /** A cast with no legal path. */
        CAST,

        /** A declared return refinement the notary cannot discharge. */
        RETURN,

        /** A conservation assertion the ledger refuses to certify. */
        CONSERVATION,

        /** An {@code assign proof f:Algebraic} claim that does not hold. */
        ALGEBRAIC
    }

    public Diagnostic {
        if (phase == null) {
            throw new IllegalArgumentException("Diagnostic phase must be non-null");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Diagnostic message must be non-blank");
        }
        if (origin == null) {
            origin = Optional.empty();
        }
    }

    /** A diagnostic with no source position. */
    public static Diagnostic at(Phase phase, String message) {
        return new Diagnostic(phase, message, Optional.empty());
    }

    /**
     * A diagnostic positioned at {@code origin}. {@link Origin#NONE} and null
     * both mean "no position", so callers can pass an exception's origin
     * straight through.
     *
     * <p>The compiler's own exceptions bracket the origin into the front of
     * their message. Since the position is carried structurally here, that
     * prefix is stripped rather than rendered twice.
     */
    public static Diagnostic at(Phase phase, String message, Origin origin) {
        if (origin == null || !origin.isPresent()) {
            return at(phase, message);
        }
        String prefix = "[" + origin + "] ";
        String text = message != null && message.startsWith(prefix)
                ? message.substring(prefix.length())
                : message;
        return new Diagnostic(phase, text, Optional.of(origin));
    }

    /** One line: {@code phase: source:line:col: message}, position omitted if unknown. */
    public String render() {
        String where = origin.map(o -> o + ": ").orElse("");
        return phase.name().toLowerCase(java.util.Locale.ROOT) + ": " + where + message;
    }

    @Override
    public String toString() {
        return render();
    }
}
