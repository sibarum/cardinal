package sibarum.cardinal.receipts;

import sibarum.cardinal.core.symbolic.SymExpr;

import java.util.List;
import java.util.Optional;

/**
 * A single path through a {@link Node}.
 *
 * <ul>
 *   <li>{@link #guard} — the arm's predicate (a symbolic claim about the parameters), or
 *       {@link Optional#empty()} for an unconditional body.
 *   <li>{@link #completion} — whether the path yields the result or leaves by throwing.
 *   <li>{@link #initialReceipts} — facts that hold on this path, transcribed deterministically from source
 *       (body equation, arm guard restated).
 *   <li>{@link #calls} — sub-call references on this path.
 * </ul>
 *
 * <p>Branches are alternatives, never conjuncts: a closing receipt referencing one branch is checked against
 * that branch's facts alone.
 *
 * <h2>Why completion exists</h2>
 *
 * <p>An expression-oriented arm always evaluates to a value, so every arm ends in a body equation. A path in a
 * statement language can instead end by throwing, and such a path is not a degenerate returning path — it is how
 * a precondition gets written:
 *
 * <pre>{@code
 * static int factorial(int n) {
 *     if (n < 0) throw new IllegalArgumentException();   // THROW: no result; constrains what survives
 *     ...                                               // n >= 0 holds from here on
 * }
 * }</pre>
 *
 * <p>A {@link Completion#THROW} branch therefore carries a guard and no result equation. Drafting from Pontif IR
 * every branch is a {@link Completion#RETURN}, and {@link #of} keeps that the quiet default.
 */
public record Branch(
        Optional<SymExpr> guard,
        Completion completion,
        List<InitialReceipt> initialReceipts,
        List<CallRef> calls) {

    /** How a path leaves the node. */
    public enum Completion {
        /** Produces the result; the path carries a body equation for it. */
        RETURN,
        /** Leaves by throwing; produces no result, and its guard constrains the surviving paths. */
        THROW
    }

    public Branch {
        if (guard == null) {
            throw new IllegalArgumentException(
                    "Branch guard must be a non-null Optional (use Optional.empty() for unconditional bodies)");
        }
        if (completion == null) {
            throw new IllegalArgumentException("Branch completion must be non-null");
        }
        initialReceipts = List.copyOf(initialReceipts);
        calls = List.copyOf(calls);
    }

    /** A returning path — the only kind an expression-language drafter produces. */
    public static Branch of(Optional<SymExpr> guard, List<InitialReceipt> receipts, List<CallRef> calls) {
        return new Branch(guard, Completion.RETURN, receipts, calls);
    }

    /** A guard clause that throws: the guard held, so the node did not return. */
    public static Branch throwing(SymExpr guard) {
        return new Branch(Optional.of(guard), Completion.THROW, List.of(), List.of());
    }

    public boolean isThrowing() {
        return completion == Completion.THROW;
    }
}
