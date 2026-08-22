package sibarum.cardinal.api;

import sibarum.cardinal.api.Diagnostic.Phase;
import sibarum.cardinal.conservation.ConservationDrafter;
import sibarum.cardinal.conservation.ConservationGraph;
import sibarum.cardinal.conservation.ConservationProofs;
import sibarum.cardinal.core.QualifiedName;
import sibarum.cardinal.core.symbolic.RewriteRule;
import sibarum.cardinal.core.symbolic.Simplifier;
import sibarum.cardinal.ir.AliasResolver;
import sibarum.cardinal.ir.AlgebraicCheck;
import sibarum.cardinal.ir.CallGate;
import sibarum.cardinal.ir.CastGate;
import sibarum.cardinal.ir.CoercionCheck;
import sibarum.cardinal.ir.CompileException;
import sibarum.cardinal.ir.CompiledModule;
import sibarum.cardinal.ir.IrCompiler;
import sibarum.cardinal.ir.IrExpr;
import sibarum.cardinal.ir.IrModule;
import sibarum.cardinal.ir.IrStmt;
import sibarum.cardinal.ir.MethodOperatorResolver;
import sibarum.cardinal.ir.ModuleLinker;
import sibarum.cardinal.ir.StaticDispatch;
import sibarum.cardinal.ir.StructTraitLowering;
import sibarum.cardinal.ir.TraitDefaultExpansion;
import sibarum.cardinal.receipts.BuiltinIssuer;
import sibarum.cardinal.receipts.Drafter;
import sibarum.cardinal.receipts.GraphReference;
import sibarum.cardinal.receipts.ProofBinding;
import sibarum.cardinal.receipts.ReceiptGraph;
import sibarum.cardinal.receipts.ReceiptGraphPrinter;
import sibarum.cardinal.receipts.Refinement;
import sibarum.cardinal.receipts.ReturnProofBinding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The compile pipeline, from parsed modules to a {@link Compilation}. One
 * instance per {@link CardinalContext}; stateless per run, so a context can
 * serve many compiles from many threads.
 *
 * <p>The stage order is not arbitrary and should not be rearranged casually:
 * linking must precede resolution because cross-module visibility is gated with
 * the symbol table; alias resolution must precede method routing or a trait
 * impl targeting an alias silently detaches; and every gate runs over the
 * resolved module so it sees ordinary dispatch calls rather than the parser's
 * placeholders.
 *
 * <p>Every gate <b>abstains on a throw</b>. A program the walk cannot classify
 * yields no error rather than a spurious one - the analyses are sound but
 * incomplete, and an incomplete analysis must not reject working code.
 */
final class Pipeline {

    /** Proof-tree head claiming a function is algebraic. */
    private static final String ALGEBRAIC_HEAD = "Algebraic";

    private final List<RewriteRule> rules;
    private final Map<String, IrModule> universe;
    private final Set<String> algebraicPrimitives;

    Pipeline(List<RewriteRule> rules, Map<String, IrModule> universe,
            Set<String> algebraicPrimitives) {
        this.rules = List.copyOf(rules);
        this.universe = Map.copyOf(universe);
        this.algebraicPrimitives = Set.copyOf(algebraicPrimitives);
    }

    /** Runs parsed modules through link, resolve, type-check and the notary gates. */
    Compilation run(Map<String, IrModule> parsed, String entryModule) {
        IrModule linked;
        try {
            linked = parsed.size() == 1
                    ? ModuleLinker.combineSingle(parsed.values().iterator().next(), universe)
                    : ModuleLinker.combine(parsed, entryModule, universe);
        } catch (CompileException ce) {
            return reject(Phase.LINK, ce.getMessage(), ce.origin());
        } catch (RuntimeException e) {
            return reject(Phase.LINK, message(e));
        }

        // Resolve aliases first, then lower struct is-a bases, expand trait
        // defaults, and route methods and operators - so everything downstream
        // sees ordinary dispatch calls. Each step is idempotent; IrCompiler
        // re-runs some of them internally to no effect.
        IrModule module;
        try {
            module = MethodOperatorResolver.resolve(
                    TraitDefaultExpansion.expand(
                            StructTraitLowering.lower(
                                    AliasResolver.resolve(linked))));
        } catch (CompileException ce) {
            return reject(Phase.RESOLVE, ce.getMessage(), ce.origin());
        } catch (RuntimeException e) {
            return reject(Phase.RESOLVE, message(e));
        }

        // Coercion checks the symbol table cannot see: no primitive-to-primitive
        // casts, and (source, target) coherence. Before the compiler lowers
        // coercions to dispatch functions.
        try {
            CoercionCheck.validate(module);
        } catch (CompileException ce) {
            return reject(Phase.TYPE, ce.getMessage(), ce.origin());
        } catch (RuntimeException e) {
            return reject(Phase.TYPE, message(e));
        }

        Simplifier simplifier = new Simplifier(rules);
        CompiledModule compiled;
        try {
            compiled = new IrCompiler(simplifier).compile(module);
        } catch (CompileException ce) {
            return reject(Phase.TYPE, ce.getMessage(), ce.origin());
        } catch (RuntimeException e) {
            return reject(Phase.TYPE, message(e));
        }

        Optional<Diagnostic> gate = callGate(module).or(() -> castGate(module));
        if (gate.isPresent()) {
            return new Compilation.Rejected(List.of(gate.get()));
        }

        List<String> abstentions = new ArrayList<>();
        List<Evidence.Discharge> discharges = new ArrayList<>();

        // The notary reasons over the alias-resolved form, so a refinement
        // mentioning an alias relates to the sort it names. Resolution already
        // succeeded above; if it somehow does not here, draft over the module as
        // it stands rather than failing a program that type-checked.
        IrModule forNotary;
        try {
            forNotary = AliasResolver.resolve(module);
        } catch (CompileException | RuntimeException e) {
            forNotary = module;
        }
        Optional<ReceiptGraph> receipts = draftReceipts(forNotary, abstentions);
        Optional<Diagnostic> returns = returnGate(module, receipts, discharges);
        if (returns.isPresent()) {
            return new Compilation.Rejected(List.of(returns.get()));
        }

        Optional<ConservationGraph.Ledger> ledger = draftConservation(forNotary, abstentions);
        Optional<Diagnostic> conservation = conservationGate(module, ledger);
        if (conservation.isPresent()) {
            return new Compilation.Rejected(List.of(conservation.get()));
        }

        Optional<Diagnostic> algebraic = algebraicGate(module);
        if (algebraic.isPresent()) {
            return new Compilation.Rejected(List.of(algebraic.get()));
        }

        return new Compilation.Checked(entryModule, module, compiled,
                new Evidence(receipts, discharges, ledger, abstentions));
    }

    // --- gates ------------------------------------------------------------

    /**
     * The dual of the return gate: at every call site, reject when the
     * arguments provably violate every overload's parameter refinements.
     * Undecided call sites abstain.
     */
    private Optional<Diagnostic> callGate(IrModule module) {
        CallGate.Report report;
        try {
            // Over the alias-resolved module, so the gate reasons about the same
            // sorts the type checker does - a union alias seen as a bare Named
            // reads as a provable misroute and would reject a valid call.
            report = CallGate.walk(AliasResolver.resolve(module));
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        List<CallGate.CallSite> failed = report.of(StaticDispatch.Verdict.FAILED);
        if (failed.isEmpty()) {
            return Optional.empty();
        }
        CallGate.CallSite c = failed.get(0);
        return Optional.of(Diagnostic.at(Phase.CALL,
                "Cannot prove the call to '" + c.functionName() + "' routes - the argument(s) "
                        + "provably violate the parameter refinement(s) of every overload. "
                        + "Narrow the arguments, supply a proof, or weaken the parameter sort.",
                c.origin()));
    }

    /** A cast with no legal path: not a render, and no declared coercion applies. */
    private Optional<Diagnostic> castGate(IrModule module) {
        Optional<IrExpr.Cast> illegal;
        try {
            illegal = CastGate.firstIllegal(module);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        return illegal.map(c -> Diagnostic.at(Phase.CAST,
                "Cannot cast to '" + castTargetName(c.targetSort()) + "' - the value is not "
                        + "renderable to String and no `cast " + castTargetName(c.targetSort())
                        + ":(x:Source) -> ...` coercion applies.",
                c.origin()));
    }

    /**
     * Every declared return refinement discharged, or the first that was not.
     * Fills {@code discharges} with the verdicts either way, so a checked
     * program carries its evidence.
     */
    private Optional<Diagnostic> returnGate(IrModule module, Optional<ReceiptGraph> drafted,
            List<Evidence.Discharge> discharges) {
        if (drafted.isEmpty()) {
            return Optional.empty();  // the drafter abstained; so does the gate
        }
        ReceiptGraph graph = drafted.get();

        // Conservation- and algebraic-headed proof trees are other gates'
        // propositions - skip them so the receipt engine does not mistake a
        // whole-function property claim for a return-refinement obligation.
        Set<String> foreignHeads = new LinkedHashSet<>(ConservationProofs.HEAD_NAMES);
        foreignHeads.add(ALGEBRAIC_HEAD);

        ProofBinding.Result bound;
        try {
            bound = ProofBinding.bind(module, graph, foreignHeads);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        if (!bound.problems().isEmpty()) {
            return Optional.of(Diagnostic.at(Phase.RETURN, bound.problems().get(0)));
        }
        Map<GraphReference, Refinement> proofs = bound.proofs();

        List<BuiltinIssuer.Attempt> attempts;
        try {
            attempts = BuiltinIssuer.attemptAll(graph, proofs);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        for (BuiltinIssuer.Attempt a : attempts) {
            discharges.add(new Evidence.Discharge(
                    graph.roots().get(a.nodeIndex()).functionName(),
                    a.nodeIndex(), a.branchIndex(),
                    ReceiptGraphPrinter.renderSym(a.obligation()),
                    a.discharged(), a.provenByRefinement()));
        }

        for (int node = 0; node < graph.roots().size(); node++) {
            final int idx = node;
            List<BuiltinIssuer.Attempt> nodeAttempts =
                    attempts.stream().filter(a -> a.nodeIndex() == idx).toList();
            if (nodeAttempts.isEmpty() || nodeAttempts.stream().allMatch(BuiltinIssuer.Attempt::discharged)) {
                continue;
            }
            String fn = graph.roots().get(idx).functionName();
            String obligation = ReceiptGraphPrinter.renderSym(nodeAttempts.get(0).obligation());
            // A proof was supplied for this node but did not discharge: stale or
            // insufficient. Re-validated per function on every compile, so an
            // unrelated edit never disturbs a valid proof.
            if (proofs.containsKey(new GraphReference(idx, 0))) {
                return Optional.of(Diagnostic.at(Phase.RETURN,
                        "The supplied proof for '" + fn + "' no longer discharges its obligation "
                                + obligation + " - it is stale or insufficient; update or remove it."));
            }
            return Optional.of(Diagnostic.at(Phase.RETURN,
                    "Cannot prove the declared return refinement of '" + fn + "': " + obligation
                            + " - prove it, weaken the declared return, or drop the narrowing."));
        }

        List<IrStmt.ReturnProof> returnProofs = new ArrayList<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.ReturnProof rp) {
                returnProofs.add(rp);
            }
        }
        try {
            return ReturnProofBinding.validate(returnProofs, graph)
                    .map(p -> Diagnostic.at(Phase.RETURN, p));
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
    }

    /**
     * Every conservation assertion certified by the ledger, or the first that
     * was not. Fail-closed at the assertion level: a program with assertions
     * whose ledger would not draft is refused, because a conservation proof
     * must never pass unchecked. A program with no assertions pays nothing.
     */
    private Optional<Diagnostic> conservationGate(IrModule module,
            Optional<ConservationGraph.Ledger> ledger) {
        ConservationProofs.Result bound;
        try {
            bound = ConservationProofs.bind(module);
        } catch (Exception | StackOverflowError e) {
            return Optional.empty();
        }
        if (!bound.problems().isEmpty()) {
            return Optional.of(Diagnostic.at(Phase.CONSERVATION, bound.problems().get(0)));
        }
        if (bound.assertions().isEmpty()) {
            return Optional.empty();
        }
        if (ledger.isEmpty()) {
            return Optional.of(Diagnostic.at(Phase.CONSERVATION,
                    "Conservation proofs could not be checked - the ledger failed to draft."));
        }
        for (Map.Entry<String, ConservationProofs.Assertion> e : bound.assertions().entrySet()) {
            Optional<ConservationGraph> graph = ledger.get().graph(e.getKey());
            if (graph.isEmpty()) {
                return Optional.of(Diagnostic.at(Phase.CONSERVATION,
                        "Conservation proof for '" + e.getKey()
                                + "' has no ledger entry - is it a function declaration?"));
            }
            Optional<String> failure =
                    ConservationProofs.evaluate(e.getKey(), e.getValue(), graph.get());
            if (failure.isPresent()) {
                return Optional.of(Diagnostic.at(Phase.CONSERVATION, failure.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * An {@code assign proof f:Algebraic} claim is a refinement on the function
     * itself: the body must be built only from the algebraic fragment, and the
     * algebraic call graph must be acyclic. Fail-closed - a false claim is a
     * hard error. Programs with no algebraic proofs pay nothing.
     */
    private Optional<Diagnostic> algebraicGate(IrModule module) {
        Set<String> claimed = new LinkedHashSet<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.Proof p && ALGEBRAIC_HEAD.equals(proofHead(p.proofTree()))) {
                claimed.add(p.functionName());
            }
        }
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        Map<String, List<IrStmt.FunctionDecl>> byName = new java.util.LinkedHashMap<>();
        for (IrStmt s : module.statements()) {
            if (s instanceof IrStmt.FunctionDecl fd) {
                byName.computeIfAbsent(fd.name(), k -> new ArrayList<>()).add(fd);
            }
        }
        Map<String, IrStmt.FunctionDecl> decls = new java.util.LinkedHashMap<>();
        for (String name : claimed) {
            List<IrStmt.FunctionDecl> fds = byName.get(name);
            if (fds == null || fds.isEmpty()) {
                return Optional.of(Diagnostic.at(Phase.ALGEBRAIC,
                        "Algebraic proof references unknown function '" + name + "'."));
            }
            if (fds.size() > 1) {
                return Optional.of(Diagnostic.at(Phase.ALGEBRAIC, "assign proof for '" + name
                        + "' targets an overloaded function - not supported yet."));
            }
            decls.put(name, fds.get(0));
        }
        return AlgebraicCheck.check(claimed, decls, algebraicPrimitives)
                .map(p -> Diagnostic.at(Phase.ALGEBRAIC, p));
    }

    // --- drafting ---------------------------------------------------------

    private Optional<ReceiptGraph> draftReceipts(IrModule module, List<String> abstentions) {
        try {
            return Optional.of(Drafter.draft(module));
        } catch (Exception | StackOverflowError e) {
            abstentions.add("the receipt graph could not be drafted (" + message(e)
                    + "), so the return gate stood down");
            return Optional.empty();
        }
    }

    private Optional<ConservationGraph.Ledger> draftConservation(
            IrModule module, List<String> abstentions) {
        try {
            return Optional.of(ConservationDrafter.draft(module));
        } catch (Exception | StackOverflowError e) {
            abstentions.add("the conservation ledger could not be drafted (" + message(e) + ")");
            return Optional.empty();
        }
    }

    // --- helpers ----------------------------------------------------------

    private static Compilation reject(Phase phase, String message) {
        return new Compilation.Rejected(List.of(Diagnostic.at(phase, message)));
    }

    private static Compilation reject(Phase phase, String message, sibarum.cardinal.core.Origin o) {
        return new Compilation.Rejected(List.of(Diagnostic.at(phase, message, o)));
    }

    /** A non-blank message for a throwable that may carry none. */
    private static String message(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
    }

    /** The local head-constructor name of a proof tree, or null. */
    private static String proofHead(IrExpr tree) {
        String name = switch (tree) {
            case IrExpr.Record r -> r.typeName();
            case IrExpr.Call c -> c.functionName();
            default -> null;
        };
        return name == null ? null : QualifiedName.memberOf(name);
    }

    private static String castTargetName(sibarum.cardinal.ir.IrSort sort) {
        return switch (sort) {
            case sibarum.cardinal.ir.IrSort.Named n -> n.name();
            case sibarum.cardinal.ir.IrSort.Refined r -> r.name();
            default -> String.valueOf(sort);
        };
    }
}
