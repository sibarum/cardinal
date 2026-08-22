package sibarum.cardinal.api;

import sibarum.cardinal.ir.CompiledModule;
import sibarum.cardinal.ir.IrModule;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The outcome of putting source through a {@link CardinalContext}: either a
 * checked program or the reasons it was refused. Nothing is executed — a
 * checked compilation is a typed, linked IR whose claims the notary has
 * discharged, which is the whole of what Cardinal produces.
 *
 * <p>Sealed, so a {@code switch} over it is exhaustive:
 *
 * {@snippet :
 * switch (cardinal.compile(source)) {
 *     case Compilation.Checked ok  -> publish(ok.ir(), ok.evidence());
 *     case Compilation.Rejected no -> no.diagnostics().forEach(d -> log(d.render()));
 * }
 * }
 */
public sealed interface Compilation {

    /**
     * A program that parsed, linked, type-checked, and had every claim
     * discharged.
     *
     * @param entryModule the module whose {@code main} is the program entry
     * @param ir          the linked, resolved IR the checks ran over
     * @param compiled    the compiled module — dispatch table, sorts, bodies
     * @param evidence    the receipt graph and its verdicts, kept as evidence
     */
    record Checked(String entryModule, IrModule ir, CompiledModule compiled, Evidence evidence)
            implements Compilation {

        public Checked {
            if (entryModule == null || entryModule.isBlank()) {
                throw new IllegalArgumentException("Checked entryModule must be non-blank");
            }
            if (ir == null || compiled == null || evidence == null) {
                throw new IllegalArgumentException("Checked ir, compiled and evidence must be non-null");
            }
        }
    }

    /**
     * A program some stage refused. Diagnostics are in the order the pipeline
     * produced them; the first is the one that stopped it.
     */
    record Rejected(List<Diagnostic> diagnostics) implements Compilation {

        public Rejected {
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("Rejected must carry at least one diagnostic");
            }
        }
    }

    /** Whether this is a {@link Checked}. */
    default boolean isChecked() {
        return this instanceof Checked;
    }

    /** The diagnostics, empty on a checked compilation. */
    default List<Diagnostic> diagnostics() {
        return this instanceof Rejected r ? r.diagnostics() : List.of();
    }

    /**
     * The checked compilation, or a {@link RejectedException} carrying every
     * diagnostic. For callers that would rather not branch — a test, a build
     * step, anything where a rejection is exceptional.
     */
    default Checked orThrow() {
        if (this instanceof Checked c) {
            return c;
        }
        throw new RejectedException((Rejected) this);
    }

    /** Thrown by {@link #orThrow()}. Carries the diagnostics, not just a message. */
    final class RejectedException extends RuntimeException {

        private final transient List<Diagnostic> diagnostics;

        RejectedException(Rejected rejected) {
            super(rejected.diagnostics().stream()
                    .map(Diagnostic::render)
                    .collect(Collectors.joining(System.lineSeparator())));
            this.diagnostics = rejected.diagnostics();
        }

        public List<Diagnostic> diagnostics() {
            return diagnostics;
        }
    }
}
