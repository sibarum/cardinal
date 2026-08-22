package sibarum.cardinal.api;

/**
 * Which front end reads a {@link Source}. Cardinal inherits two over one IR,
 * which is the seam the fork was built on.
 */
public enum Syntax {

    /** The surface language. */
    SURFACE,

    /**
     * The S-expression reference syntax — the stable ground truth the language
     * suite is written against, and the only one a {@link CardinalContext}'s
     * {@code language} definition reshapes.
     */
    SEXPR
}
