package sibarum.cardinal.api;

/**
 * The way in.
 *
 * {@snippet :
 * CardinalContext cardinal = Cardinal.context()
 *         .runner(new FilesystemRunner())
 *         .build();
 *
 * switch (cardinal.compile(Source.of("query.cdl", text))) {
 *     case Compilation.Checked ok  -> describe(ok.ir(), ok.evidence());
 *     case Compilation.Rejected no -> no.diagnostics().forEach(d -> log(d.render()));
 * }
 * }
 *
 * <p>Cardinal is a front end. It reads a typed, pattern-matched,
 * refinement-checked program and produces an IR whose claims have been
 * discharged. It does not run anything: what a query means is the
 * {@link Runner}'s business, and running it is the embedder's.
 */
public final class Cardinal {

    private Cardinal() {}

    /** A builder for a context. Register runners on it, then {@code build()}. */
    public static CardinalContext.Builder context() {
        return new CardinalContext.Builder();
    }

    /**
     * A context with no runners: the default language, the production rules,
     * and an empty module universe. Enough to compile a self-contained program,
     * and the right starting point for a test.
     */
    public static CardinalContext bare() {
        return context().build();
    }
}
