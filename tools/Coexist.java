import java.io.File;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Cardinal and Pontif must be able to sit in one runtime without conflicting.
 * That assumption is load-bearing -- a service speaking descriptors while a
 * Pontif program executes alongside it is the point -- so it is checked here
 * rather than argued for.
 *
 * <p>Run it through {@code tools/coexist.sh}, which builds both projects and
 * puts every jar from each on one classpath. Three things are asserted:
 *
 * <ol>
 *   <li>no class file name appears in both sets of jars, which is the hard
 *       conflict: the loader would pick one and say nothing;
 *   <li>one loader resolves a parser from each project, and they are distinct
 *       classes rather than one shadowing the other;
 *   <li>Cardinal registers no Truffle language, so there is no id for a
 *       polyglot context to arbitrate.
 * </ol>
 */
public final class Coexist {

    public static void main(String[] args) throws Exception {
        Map<String, String> owner = new HashMap<>();
        List<String> clashes = new ArrayList<>();
        List<String> cardinalLanguages = new ArrayList<>();
        boolean pontifRegistersALanguage = false;
        int classes = 0;

        for (String path : args) {
            try (JarFile jar = new JarFile(new File(path))) {
                for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                    String name = e.nextElement().getName();
                    if (!name.endsWith(".class")) {
                        continue;
                    }
                    classes++;
                    String prev = owner.putIfAbsent(name, path);
                    if (prev != null) {
                        clashes.add(name + "\n        in " + prev + "\n        in " + path);
                    }
                    if (name.equals("sibarum/pontif/core/PontifLanguage.class")) {
                        pontifRegistersALanguage = true;
                    }
                    if (name.startsWith("sibarum/cardinal/") && name.endsWith("Language.class")) {
                        cardinalLanguages.add(name);
                    }
                }
            }
        }

        System.out.println("jars:                       " + args.length);
        System.out.println("classes on one classpath:   " + classes);
        System.out.println("colliding class names:      " + clashes.size());
        for (String c : clashes) {
            System.out.println("      " + c);
        }

        Class<?> pontif = Class.forName("sibarum.pontif.parser.PontifParser");
        Class<?> cardinal = Class.forName("sibarum.cardinal.parser.CardinalParser");
        boolean distinct = pontif != cardinal
                && !pontif.getName().equals(cardinal.getName());
        System.out.println("both parsers resolve:       " + pontif.getName()
                + " / " + cardinal.getName());
        System.out.println("distinct classes:           " + distinct);
        System.out.println("pontif registers language:  " + pontifRegistersALanguage);
        System.out.println("cardinal registers language:" + (cardinalLanguages.isEmpty()
                ? " no" : " " + cardinalLanguages));

        boolean ok = clashes.isEmpty() && distinct && cardinalLanguages.isEmpty();
        System.out.println(ok ? "COEXISTENCE: ok" : "COEXISTENCE: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }
}
