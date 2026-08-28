package sibarum.cardinal.receipts;

import java.util.List;

/**
 * A call site in the receipt-graph. Sub-call references (including recursive back-references) point at a node by
 * {@link #id}, not by Node identity.
 *
 * <p>{@link #branches} carries one entry per arm of an internal match, or a single unconditional branch if the
 * body has no match.
 *
 * <h2>Identity</h2>
 *
 * <p>{@link #id} is the identity a {@link CallRef} resolves against; {@link #displayName} is what a human reads.
 * They differ because different drafters can pin a target to different precision, and the graph should not force
 * either of them to lie:
 *
 * <ul>
 *   <li>Drafting from Pontif IR, overload pinning is not yet available, so the id <em>is</em> the function name.
 *       Several overloads then share an id, and a reference is to the function by name — the long-standing
 *       deferral, unchanged.
 *   <li>Drafting from Java, overloads are pervasive and the same simple name recurs across unrelated types, so
 *       the id is a fully-qualified method signature. A back-reference then lands on exactly one node, which is
 *       what the no-duplicate-edges rule needs in order to mean anything.
 * </ul>
 *
 * <p>An opaque id rather than a structured key is deliberate: a structured key with a parameter-signature field
 * would oblige the IR drafter to invent a signature it does not know, and a fabricated key is worse than a
 * frankly imprecise one.
 */
public record Node(
        String id,
        String displayName,
        List<Param> params,
        Var resultVar,
        List<Branch> branches,
        boolean synthetic) {

    public Node {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("Node id must be non-empty");
        }
        if (displayName == null || displayName.isEmpty()) {
            throw new IllegalArgumentException("Node displayName must be non-empty");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("Node resultVar must be non-null");
        }
        params = List.copyOf(params);
        branches = List.copyOf(branches);
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("Node must have at least one branch");
        }
    }

    /**
     * A node whose identity is its name — the IR drafter's case, where overloads share an id.
     */
    public static Node named(String functionName, List<Param> params, Var resultVar, List<Branch> branches) {
        return new Node(functionName, functionName, params, resultVar, branches, false);
    }

    /**
     * A node the drafter synthesised rather than found: today the per-frame step of a hoisted iteration, and the
     * tail-recursive lowering of a loop when drafting from a language whose loops have no callee.
     */
    public static Node synthetic(String id, String displayName,
                                 List<Param> params, Var resultVar, List<Branch> branches) {
        return new Node(id, displayName, params, resultVar, branches, true);
    }

    /** A node with an id distinct from its display name — a drafter that can pin its targets exactly. */
    public static Node identified(String id, String displayName,
                                  List<Param> params, Var resultVar, List<Branch> branches) {
        return new Node(id, displayName, params, resultVar, branches, false);
    }

    /**
     * The name a human reads, which for an IR-drafted node is also its id.
     *
     * @deprecated prefer {@link #displayName()} when you mean the label, or {@link #id()} when you mean identity.
     *     The two were one field before drafters existed that could tell overloads apart.
     */
    @Deprecated
    public String functionName() {
        return displayName;
    }

    /** The paths that produce a result, as opposed to those that leave by throwing. */
    public List<Branch> returningBranches() {
        return branches.stream().filter(b -> !b.isThrowing()).toList();
    }
}
