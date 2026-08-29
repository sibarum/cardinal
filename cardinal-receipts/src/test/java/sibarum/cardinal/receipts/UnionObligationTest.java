package sibarum.cardinal.receipts;

import org.junit.jupiter.api.Test;
import sibarum.cardinal.core.symbolic.SymExpr;
import sibarum.cardinal.core.types.Sort;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A result sort that is a union obliges the conjunction over its refined arms, each on the paths that can
 * produce it.
 *
 * <p>This is what a statement language needs: a method returns a value <em>or</em> it throws, so its result is
 * {@code T | Throwable}, and the arms differ rather than the totality. A path that throws is not asked to prove
 * a claim about the value arm.
 */
class UnionObligationTest {

    private static final Sort THROWABLE = Sort.of("Throwable");

    private static SymExpr var(String n) { return SymExpr.var(n); }
    private static SymExpr lit(long v) { return SymExpr.lit(v); }
    private static SymExpr cmp(SymExpr l, SymExpr.CmpOp op, SymExpr r) { return SymExpr.cmp(l, op, r); }

    /** {@code @ >= bound}. */
    private static SymExpr atLeast(long bound) {
        return cmp(SymExpr.self(), SymExpr.CmpOp.GE, lit(bound));
    }

    /**
     * A node shaped like a guarded method: one throwing path, one returning path whose result is a literal.
     *
     * @param resultSort the whole result sort, union or not
     */
    private static Node guardedNode(Sort resultSort, long returned) {
        Branch rejects = Branch.throwing(cmp(var("n"), SymExpr.CmpOp.LT, lit(0)));
        Branch returns = Branch.of(
                Optional.of(cmp(var("n"), SymExpr.CmpOp.GE, lit(0))),
                List.of(new InitialReceipt(cmp(var("r_0"), SymExpr.CmpOp.EQ, lit(returned)))),
                List.of());
        return Node.identified("demo#f(int)", "f",
                List.of(new Param("n", Sort.of("Int"))),
                new Var("r_0", resultSort),
                List.of(rejects, returns));
    }

    @Test
    void anUnrefinedUnionObligesNothing() {
        ReceiptGraph graph = new ReceiptGraph(List.of(
                guardedNode(Sort.union(List.of(Sort.of("Int"), THROWABLE)), 1)));

        assertTrue(BuiltinIssuer.attemptAll(graph).isEmpty(),
                "no arm carries a predicate, so there is nothing to prove");
    }

    @Test
    void arefinedValueArmIsAttemptedOnReturningPathsOnly() {
        Sort result = Sort.union(List.of(Sort.refined("Int", atLeast(1)), THROWABLE));
        ReceiptGraph graph = new ReceiptGraph(List.of(guardedNode(result, 1)));

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        assertEquals(1, attempts.size(),
                "one refined arm, and only the returning path can produce it: " + attempts);
        assertEquals(1, attempts.get(0).branchIndex(), "branch 0 throws; branch 1 returns");
        assertTrue(attempts.get(0).discharged(), "r_0 == 1 discharges r_0 >= 1");

        List<ClosingReceipt> closings = BuiltinIssuer.close(graph);
        assertEquals(1, closings.size());
        assertTrue(Notary.hypothesisSupported(graph, closings.get(0)).accepted());
    }

    @Test
    void aThrowingPathIsNotAskedToProveTheValueArmAndIsNotClaimedToHave() {
        // The returning path violates the refinement, so nothing discharges. The throwing path must not appear
        // as a vacuous success — there is no obligation there to report on at all.
        Sort result = Sort.union(List.of(Sort.refined("Int", atLeast(1)), THROWABLE));
        ReceiptGraph graph = new ReceiptGraph(List.of(guardedNode(result, 0)));

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        assertEquals(1, attempts.size(), "still only the returning path is obliged");
        assertFalse(attempts.get(0).discharged(), "r_0 == 0 does not discharge r_0 >= 1");
        assertTrue(BuiltinIssuer.close(graph).isEmpty(), "so nothing is closed, on either path");
    }

    @Test
    void everyRefinedArmOfAUnionContributesAnObligation() {
        Sort result = Sort.union(List.of(
                Sort.refined("Int", atLeast(1)),
                Sort.refined("Int", cmp(SymExpr.self(), SymExpr.CmpOp.LE, lit(10))),
                THROWABLE));
        ReceiptGraph graph = new ReceiptGraph(List.of(guardedNode(result, 1)));

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        assertEquals(2, attempts.size(), "the conjunction over refined arms, one attempt each: " + attempts);
        assertTrue(attempts.stream().allMatch(BuiltinIssuer.Attempt::discharged),
                "1 satisfies both >= 1 and <= 10");
    }

    /**
     * The domain comes from the arm, not the union.
     *
     * <p>Routing on the union's name would read {@code "Decimal | Throwable"}, match nothing, and fall to the
     * integer engine — whose {@code > 0 implies >= 1} cut is false for a decimal. {@code 0.5} is the
     * counterexample, and the failure would be silent.
     */
    @Test
    void aDecimalArmIsDischargedDenselyEvenInsideAUnion() {
        Sort decimalArm = Sort.refined("Decimal", atLeast(1));
        Sort result = Sort.union(List.of(decimalArm, THROWABLE));

        // The path says only that the result is positive. Over the reals that does not give >= 1.
        Branch returns = Branch.of(
                Optional.empty(),
                List.of(new InitialReceipt(cmp(var("r_0"), SymExpr.CmpOp.GT, lit(0)))),
                List.of());
        Node node = Node.identified("demo#g()", "g", List.of(), new Var("r_0", result), List.of(returns));
        ReceiptGraph graph = new ReceiptGraph(List.of(node));

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        assertEquals(1, attempts.size());
        assertFalse(attempts.get(0).discharged(),
                "r_0 > 0 must not discharge r_0 >= 1 in the decimal domain; 0.5 is the counterexample");

        // The same shape over Int does discharge, which is what makes the routing observable.
        Sort intResult = Sort.union(List.of(Sort.refined("Int", atLeast(1)), THROWABLE));
        Node intNode = Node.identified("demo#h()", "h", List.of(), new Var("r_0", intResult), List.of(returns));
        assertTrue(BuiltinIssuer.attemptAll(new ReceiptGraph(List.of(intNode))).get(0).discharged(),
                "over the integers r_0 > 0 does give r_0 >= 1");
    }

    @Test
    void aPlainRefinedSortStillObligesEveryPath() {
        // Unchanged behaviour for the non-union case: the obligation applies to throwing paths too, where it
        // discharges vacuously against contradictory hypotheses.
        ReceiptGraph graph = new ReceiptGraph(List.of(guardedNode(Sort.refined("Int", atLeast(1)), 1)));

        List<BuiltinIssuer.Attempt> attempts = BuiltinIssuer.attemptAll(graph);
        assertEquals(2, attempts.size(), "both paths are obliged when the sort is not a union");
    }
}
