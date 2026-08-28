package sibarum.cardinal.receipts;

import java.util.List;
import java.util.Optional;

/**
 * Cardinal's receipt-graph: the immutable data structure a drafter produces from source. One artifact per
 * drafting run; an ordered list of {@link Node}s, one per function or method declaration, plus the synthetic
 * nodes a drafter hoists out of bodies.
 *
 * <p>Order is source declaration order, preserved, so two drafts of unchanged source are comparable element by
 * element — which is what a skeleton-match check needs.
 *
 * <p>Calls are encoded by {@link Node#id()} via {@link CallRef#targetId()}; a CallRef whose id matches a node in
 * {@link #roots} <em>is</em> the back-reference. How precise an id is depends on the drafter: from Pontif IR it
 * is a function name, so overloads share it; from Java it is a fully-qualified signature, so they do not. See
 * {@link Node} on identity.
 *
 * <p>See {@code docs/receipt-graph.md} for the design and worked example.
 */
public record ReceiptGraph(List<Node> roots) {

    public ReceiptGraph {
        if (roots == null) {
            throw new IllegalArgumentException("ReceiptGraph roots must be non-null");
        }
        roots = List.copyOf(roots);
    }

    /** All nodes whose display name is {@code functionName}, in declaration order. */
    public List<Node> nodesNamed(String functionName) {
        return roots.stream()
                .filter(n -> n.displayName().equals(functionName))
                .toList();
    }

    /**
     * The node a call resolves to, when its id pins exactly one.
     *
     * <p>Empty means the reference is not an edge: the target may be outside the drafted set, or — where a
     * drafter cannot pin overloads — the id may name several nodes, in which case nothing here guesses which.
     * Use {@link #nodesWithId(String)} to see the candidates.
     */
    public Optional<Node> nodeWithId(String id) {
        List<Node> matches = nodesWithId(id);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    /** Every node sharing {@code id}. More than one only where a drafter cannot tell overloads apart. */
    public List<Node> nodesWithId(String id) {
        return roots.stream().filter(n -> n.id().equals(id)).toList();
    }

    /** The node {@code call} points at, when it points at exactly one. */
    public Optional<Node> target(CallRef call) {
        return nodeWithId(call.targetId());
    }

    /**
     * Whether this call is an edge in the graph at all — that is, whether its target was drafted. A call to
     * something outside the drafted set is an opaque value, not an edge.
     */
    public boolean isBackReference(CallRef call) {
        return !nodesWithId(call.targetId()).isEmpty();
    }
}
