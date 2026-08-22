package sibarum.cardinal.core.symbolic.algebra;

import sibarum.cardinal.core.symbolic.SymExpr;

public record BasisElement(String name, SymExpr expression) {

    public BasisElement {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Basis name cannot be null or empty");
        }
        if (expression == null) {
            throw new IllegalArgumentException("Basis expression cannot be null");
        }
    }
}
