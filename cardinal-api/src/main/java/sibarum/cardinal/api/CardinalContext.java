package sibarum.cardinal.api;

import sibarum.cardinal.core.symbolic.RewriteRule;
import sibarum.cardinal.defaults.DefaultRules;
import sibarum.cardinal.ir.IrModule;
import sibarum.cardinal.ir.NamespaceAssembler;
import sibarum.cardinal.parser.CardinalParser;
import sibarum.cardinal.parser.LanguageDef;
import sibarum.cardinal.parser.ParseException;
import sibarum.cardinal.parser.SexprParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A configured Cardinal front end: a language definition, a set of simplifier
 * rules, and the runners whose symbols are in scope. Source goes in;
 * {@link Compilation} comes out.
 *
 * <p><b>Nothing is executed.</b> A context parses, links, infers types, checks
 * refinements and discharges claims. What it produces is a typed IR and the
 * notary's evidence for it. Where that program's values come from, and what
 * evaluating it means, belongs to a runner.
 *
 * <p>Build one with {@link Cardinal#context()}:
 *
 * {@snippet :
 * CardinalContext cardinal = Cardinal.context()
 *         .runner(new FilesystemRunner())
 *         .build();
 *
 * Compilation result = cardinal.compile(Source.of("query.cdl", text));
 * }
 *
 * <p><b>Immutable and thread-safe.</b> Building parses every runner's modules
 * once, so a runner contributing source that does not parse fails the build
 * rather than the first compile. Each {@link #compile} allocates its own
 * simplifier and compiler, so one context serves any number of concurrent
 * compiles. Reuse it - building is the expensive part.
 *
 * <p>Two contexts in one JVM do not interfere. The module universe is held per
 * context rather than in a process-global registry, so a context with a
 * filesystem runner and a context with a SQL runner can be alive at the same
 * time with different vocabularies and neither can shadow the other.
 */
public final class CardinalContext {

    private final LanguageDef language;
    private final List<RewriteRule> rules;
    private final List<Runner> runners;
    private final Map<String, IrModule> universe;
    private final Set<String> algebraicPrimitives;
    private final Pipeline pipeline;

    private CardinalContext(Builder b) {
        this.language = b.language;
        this.rules = List.copyOf(b.rules);
        this.runners = List.copyOf(b.runners);

        Map<String, IrModule> parsed = new LinkedHashMap<>();
        Set<String> primitives = new LinkedHashSet<>();
        for (Runner runner : b.runners) {
            for (Source source : runner.modules()) {
                put(parsed, parse(source, language, "runner '" + runner.name() + "'"),
                        "runner '" + runner.name() + "'");
            }
            primitives.addAll(runner.algebraicPrimitives());
        }
        for (Source source : b.modules) {
            put(parsed, parse(source, language, "context module"), "context module");
        }
        this.universe = Map.copyOf(parsed);
        this.algebraicPrimitives = Set.copyOf(primitives);
        this.pipeline = new Pipeline(rules, universe, algebraicPrimitives);
    }

    /**
     * The language definition. Note that it reshapes the S-expression syntax
     * only - the surface parser does not consult it.
     */
    public LanguageDef language() {
        return language;
    }

    /** The simplifier rewrite rules this context checks refinements with. */
    public List<RewriteRule> rules() {
        return rules;
    }

    /** The registered runners, in registration order. */
    public List<Runner> runners() {
        return runners;
    }

    /**
     * The modules this context can link against, by name - everything its
     * runners contribute plus anything added directly. Only those a program
     * {@code requires} are injected into its compilation.
     */
    public Map<String, IrModule> universe() {
        return universe;
    }

    /** The runner whose declared capability covers {@code symbol}, if any. */
    public Optional<Runner> runnerFor(String symbol) {
        return runners.stream().filter(r -> r.capability().claims(symbol)).findFirst();
    }

    /**
     * Compiles one source. Its declared module name is the entry module.
     *
     * <p>Never throws for bad input: a program that will not compile comes back
     * as {@link Compilation.Rejected} with diagnostics. Call
     * {@link Compilation#orThrow()} if you would rather it did.
     */
    public Compilation compile(Source source) {
        return compile(List.of(source));
    }

    /**
     * Compiles several sources as one program, inferring the entry module: the
     * sole module with a non-trivial {@code main}. Sources declaring the same
     * module name are folded into one module.
     */
    public Compilation compile(List<Source> sources) {
        Map<String, IrModule> parsed;
        try {
            parsed = parseAll(sources);
        } catch (Rejection r) {
            return r.rejected;
        }
        String entry = soleModuleWithMain(parsed);
        if (entry == null) {
            return new Compilation.Rejected(List.of(Diagnostic.at(Diagnostic.Phase.LINK,
                    "No entry module: exactly one module must have a main expression, but "
                            + describeMains(parsed) + ". Name one with compile(sources, entry).")));
        }
        return pipeline.run(parsed, entry);
    }

    /**
     * Compiles several sources as one program with a named entry module - the
     * one whose {@code main} is the program entry.
     */
    public Compilation compile(List<Source> sources, String entryModule) {
        Map<String, IrModule> parsed;
        try {
            parsed = parseAll(sources);
        } catch (Rejection r) {
            return r.rejected;
        }
        if (!parsed.containsKey(entryModule)) {
            return new Compilation.Rejected(List.of(Diagnostic.at(Diagnostic.Phase.LINK,
                    "Entry module '" + entryModule + "' is not among the sources: "
                            + parsed.keySet())));
        }
        return pipeline.run(parsed, entryModule);
    }

    // --- parsing ----------------------------------------------------------

    /** Parses every source, folding same-named modules. Throws {@link Rejection} on failure. */
    private Map<String, IrModule> parseAll(List<Source> sources) {
        if (sources.isEmpty()) {
            throw new Rejection(new Compilation.Rejected(List.of(
                    Diagnostic.at(Diagnostic.Phase.PARSE, "No sources to compile."))));
        }
        Map<String, List<IrModule>> byName = new LinkedHashMap<>();
        for (Source source : sources) {
            IrModule m;
            try {
                m = parseOrThrow(source, language);
            } catch (ParseException pe) {
                throw new Rejection(new Compilation.Rejected(List.of(
                        Diagnostic.at(Diagnostic.Phase.PARSE, pe.getMessage(), pe.origin()))));
            } catch (RuntimeException e) {
                throw new Rejection(new Compilation.Rejected(List.of(
                        Diagnostic.at(Diagnostic.Phase.PARSE,
                                e.getMessage() == null ? e.toString() : e.getMessage()))));
            }
            byName.computeIfAbsent(m.name(), k -> new ArrayList<>()).add(m);
        }
        Map<String, IrModule> merged = new LinkedHashMap<>();
        for (Map.Entry<String, List<IrModule>> e : byName.entrySet()) {
            try {
                merged.put(e.getKey(), e.getValue().size() == 1
                        ? e.getValue().get(0)
                        : NamespaceAssembler.merge(e.getKey(), e.getValue()));
            } catch (sibarum.cardinal.ir.CompileException ce) {
                throw new Rejection(new Compilation.Rejected(List.of(
                        Diagnostic.at(Diagnostic.Phase.LINK, ce.getMessage(), ce.origin()))));
            }
        }
        return merged;
    }

    private static IrModule parseOrThrow(Source source, LanguageDef language)
            throws ParseException {
        return switch (source.syntax()) {
            case SURFACE -> CardinalParser.parseModule(source.text(), source.name());
            case SEXPR -> SexprParser.parseModule(source.text(), source.name(), language);
        };
    }

    /** Parses a context-owned source, where a failure is a setup error rather than input. */
    private static IrModule parse(Source source, LanguageDef language, String who) {
        try {
            return parseOrThrow(source, language);
        } catch (ParseException pe) {
            throw new SetupException(who + " contributed a module that does not parse ("
                    + source.name() + "): " + pe.getMessage(), pe);
        } catch (RuntimeException e) {
            throw new SetupException(who + " contributed a module that does not parse ("
                    + source.name() + "): " + e.getMessage(), e);
        }
    }

    private static void put(Map<String, IrModule> into, IrModule module, String who) {
        IrModule clash = into.putIfAbsent(module.name(), module);
        if (clash != null) {
            throw new SetupException("Two modules named '" + module.name()
                    + "' are in scope; " + who + " contributed the second. A context's module "
                    + "universe is keyed by name, so the names must be distinct.");
        }
    }

    /** The single module whose {@code main} is not the trivial placeholder, or null. */
    private static String soleModuleWithMain(Map<String, IrModule> modules) {
        String found = null;
        for (Map.Entry<String, IrModule> e : modules.entrySet()) {
            if (!isTrivialMain(e.getValue())) {
                if (found != null) {
                    return null;
                }
                found = e.getKey();
            }
        }
        // A single source with no main is still that source's compilation.
        return found != null ? found
                : modules.size() == 1 ? modules.keySet().iterator().next() : null;
    }

    private static boolean isTrivialMain(IrModule m) {
        return m.main() instanceof sibarum.cardinal.ir.IrExpr.Lit l
                && Long.valueOf(0L).equals(l.value());
    }

    private static String describeMains(Map<String, IrModule> modules) {
        List<String> withMain = modules.entrySet().stream()
                .filter(e -> !isTrivialMain(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        return withMain.isEmpty() ? "none does" : "these do: " + withMain;
    }

    /** Internal control flow for a parse failure inside a compile. */
    private static final class Rejection extends RuntimeException {
        private final transient Compilation.Rejected rejected;

        Rejection(Compilation.Rejected rejected) {
            super(null, null, false, false);
            this.rejected = rejected;
        }
    }

    /**
     * A context could not be built, or was built wrong - a programming error in
     * the embedding, not something a user's source did. Setup failures throw;
     * only compilation failures come back as diagnostics.
     */
    public static final class SetupException extends RuntimeException {
        SetupException(String message) {
            super(message);
        }

        SetupException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // --- builder ----------------------------------------------------------

    /**
     * Collects a context's configuration. Not thread-safe; build one context
     * and share that.
     */
    public static final class Builder {

        private LanguageDef language = LanguageDef.defaults();
        private List<RewriteRule> rules = DefaultRules.production();
        private final List<Runner> runners = new ArrayList<>();
        private final List<Source> modules = new ArrayList<>();

        Builder() {}

        /**
         * The language definition for the S-expression syntax - keywords,
         * operator spellings, reserved words. The surface parser does not
         * consult it, so this changes how {@link Syntax#SEXPR} reads and
         * nothing else.
         */
        public Builder language(LanguageDef language) {
            if (language == null) {
                throw new IllegalArgumentException("language must be non-null");
            }
            this.language = language;
            return this;
        }

        /**
         * The simplifier rewrite rules refinement checking runs with. Defaults
         * to {@link DefaultRules#production()}; narrow it only if you know what
         * a missing rule costs the notary.
         */
        public Builder rules(List<RewriteRule> rules) {
            if (rules == null) {
                throw new IllegalArgumentException("rules must be non-null");
            }
            this.rules = List.copyOf(rules);
            return this;
        }

        /**
         * Registers a runner. Its modules are parsed at {@link #build()} and
         * put in scope for any program that {@code requires} them; a program
         * that requires none is unaffected by it.
         */
        public Builder runner(Runner runner) {
            if (runner == null) {
                throw new IllegalArgumentException("runner must be non-null");
            }
            runners.add(runner);
            return this;
        }

        /** Registers several runners, in order. */
        public Builder runners(Runner... runners) {
            for (Runner r : runners) {
                runner(r);
            }
            return this;
        }

        /**
         * Puts a module in scope without a runner behind it - a shared prelude,
         * a type vocabulary, anything a program may {@code requires} that has
         * no data source to go with it.
         */
        public Builder module(Source module) {
            if (module == null) {
                throw new IllegalArgumentException("module must be non-null");
            }
            modules.add(module);
            return this;
        }

        /**
         * Builds the context, parsing every contributed module.
         *
         * @throws SetupException if a contributed module does not parse, or two
         *                        contributed modules share a name
         */
        public CardinalContext build() {
            return new CardinalContext(this);
        }
    }
}
