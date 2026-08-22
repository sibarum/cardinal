package sibarum.cardinal.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A named unit of Cardinal source. The name is what diagnostics cite, so give
 * it something a reader can find — a file path, a query id, a request id.
 *
 * <p>Immutable. The text is not parsed here; a source is just the bytes plus
 * enough context to report on them.
 */
public record Source(String name, String text, Syntax syntax) {

    public Source {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Source name must be non-blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("Source text must be non-null (empty is fine)");
        }
        if (syntax == null) {
            throw new IllegalArgumentException("Source syntax must be non-null");
        }
    }

    /** Surface-syntax source. */
    public static Source of(String name, String text) {
        return new Source(name, text, Syntax.SURFACE);
    }

    /** S-expression reference-syntax source. */
    public static Source sexpr(String name, String text) {
        return new Source(name, text, Syntax.SEXPR);
    }

    /**
     * Surface-syntax source read from a file, named by its path. The only
     * method here that touches the filesystem — {@code cardinal-ir} and
     * {@code cardinal-core} stay I/O-free, and this module does the reading.
     *
     * @throws UncheckedIOException if the file cannot be read
     */
    public static Source ofFile(Path path) {
        try {
            return of(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read Cardinal source: " + path, e);
        }
    }

    /** The same source under a different name — useful when a buffer stands in for a file. */
    public Source named(String newName) {
        return new Source(newName, text, syntax);
    }
}
