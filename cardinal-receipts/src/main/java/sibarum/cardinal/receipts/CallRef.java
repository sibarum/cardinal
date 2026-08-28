package sibarum.cardinal.receipts;

import sibarum.cardinal.core.symbolic.SymExpr;

import java.util.List;

/**
 * A sub-call on a {@link Branch}, pointing at a {@link Node} by {@link Node#id()} rather than expanding it.
 *
 * <p>When {@link #targetId} matches a node already in the graph, the reference <em>is</em> the back-edge, and two
 * things follow at no cost: recursion cannot unfold infinitely, so well-foundedness is a property of the graph
 * rather than a separate proof rule; and the edge carries the target's postcondition along as an inductive
 * hypothesis, so nothing has to decide to assume it.
 *
 * <p>{@link #targetId} may name nothing in the graph, and that is legitimate — a call into a dependency, or one of
 * the opaque hoists the drafter emits for a value-position match. Such a reference is not an edge; it is a value
 * carrying whatever refinement its {@link #resultVar} sort states.
 */
public record CallRef(
        String targetId,
        List<SymExpr> argBindings,
        Var resultVar) {

    public CallRef {
        if (targetId == null || targetId.isEmpty()) {
            throw new IllegalArgumentException("CallRef targetId must be non-empty");
        }
        if (resultVar == null) {
            throw new IllegalArgumentException("CallRef resultVar must be non-null");
        }
        argBindings = List.copyOf(argBindings);
    }

    /**
     * The target's identity, under its former name.
     *
     * @deprecated prefer {@link #targetId()}. The component was named for a time when a node's identity could
     *     only ever be a function name.
     */
    @Deprecated
    public String targetFunctionName() {
        return targetId;
    }
}
