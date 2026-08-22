package sibarum.cardinal.api;

import sibarum.cardinal.conservation.ConservationGraph;
import sibarum.cardinal.receipts.ReceiptGraph;
import sibarum.cardinal.receipts.ReceiptGraphPrinter;

import java.util.List;
import java.util.Optional;

/**
 * What the notary found, kept rather than thrown away.
 *
 * <p>A compilation only reaches {@link Compilation.Checked} when no obligation
 * was left undischarged — an outstanding one is a rejection, not a warning — so
 * on a checked compilation {@link #allDischarged()} is true by construction.
 * The value of holding the artifacts is that they are the evidence: a consumer
 * receiving a descriptor is meant to re-prove its claims locally rather than
 * believe them, and this is what it would re-prove against.
 *
 * <p>The notary <b>abstains</b> rather than guesses. A program outside the
 * drafter's current scope produces no graph, and the gates that depend on it
 * stand down instead of rejecting — the drafter's gaps must not punish
 * otherwise-valid code. {@link #abstentions()} says when that happened and why,
 * so an abstention is visible rather than indistinguishable from a clean bill.
 *
 * @param receipts     the receipt graph, one node per function declaration in
 *                     source order, when it could be drafted
 * @param discharges   one entry per obligation the receipt engine attempted
 * @param conservation the dataflow ledger, when it could be drafted
 * @param abstentions  why the notary could not reach a verdict, where it could not
 */
public record Evidence(
        Optional<ReceiptGraph> receipts,
        List<Discharge> discharges,
        Optional<ConservationGraph.Ledger> conservation,
        List<String> abstentions) {

    /**
     * One proof obligation and its verdict.
     *
     * @param functionName       the function whose declared return was at stake
     * @param obligation         the goal, rendered
     * @param discharged         whether it was proved
     * @param provenByRefinement whether a supplied proof closed it rather than
     *                           the built-in engine
     */
    public record Discharge(
            String functionName,
            int nodeIndex,
            int branchIndex,
            String obligation,
            boolean discharged,
            boolean provenByRefinement) {}

    public Evidence {
        receipts = receipts == null ? Optional.empty() : receipts;
        conservation = conservation == null ? Optional.empty() : conservation;
        discharges = List.copyOf(discharges == null ? List.of() : discharges);
        abstentions = List.copyOf(abstentions == null ? List.of() : abstentions);
    }

    /** Whether every attempted obligation was discharged. */
    public boolean allDischarged() {
        return discharges.stream().allMatch(Discharge::discharged);
    }

    /** The obligations that were not discharged, in graph order. */
    public List<Discharge> outstanding() {
        return discharges.stream().filter(d -> !d.discharged()).toList();
    }

    /** Whether the notary reached a verdict on everything it was asked about. */
    public boolean abstained() {
        return !abstentions.isEmpty();
    }

    /** The receipt graph printed, or the reason there is none. */
    public String render() {
        return receipts.map(ReceiptGraphPrinter::print)
                .orElseGet(() -> "(no receipt graph) " + String.join("; ", abstentions));
    }
}
