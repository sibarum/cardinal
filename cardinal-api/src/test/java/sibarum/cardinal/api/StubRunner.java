package sibarum.cardinal.api;

import java.util.List;
import java.util.Set;

/** A runner that contributes fixed modules and nothing else. */
record StubRunner(String name, List<Source> modules, Capability capability) implements Runner {

    StubRunner(String name, Source... modules) {
        this(name, List.of(modules), Capability.local());
    }

    @Override
    public Set<String> algebraicPrimitives() {
        return Set.of();
    }
}
