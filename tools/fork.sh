#!/bin/sh
#
# Cardinal = f(Pontif).
#
# f takes a Pontif checkout and produces the Cardinal source tree: it copies
# seven modules, deletes the files that reach into Truffle, patches the two
# that only half do, and renames sibarum.pontif to sibarum.cardinal throughout.
#
# The point of keeping f as a script rather than as a pile of hand edits is
# that adopting an upstream change becomes f applied to a patch. When f stops
# applying cleanly that is information -- upstream moved something structural
# -- and it is better to learn it from a failing script than from a silent
# merge. So f fails loudly: every file it expects to delete must exist, every
# patch must apply, and the verification at the end is fatal, not advisory.
#
#   usage: tools/fork.sh <path-to-pontif-checkout> [dest]
#
set -eu

SRC=${1:?usage: tools/fork.sh <path-to-pontif-checkout> [dest]}
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DEST=${2:-$(CDPATH= cd -- "$HERE/.." && pwd)}

say() { printf '%s\n' "$*"; }
die() { printf 'fork.sh: %s\n' "$*" >&2; exit 1; }

# --- what f is ------------------------------------------------------------

# Taken whole: the front end, the IR, and the notary.
TAKE='core ir parser predicates conservation receipts defaults'

# Cardinal's own, hand-written, never generated. f does not touch these beyond
# listing them in the reactor and the dependency management. They may depend on
# the taken modules; nothing taken may depend on them.
OWN='api'

# Grafted: individual files lifted out of a module Cardinal does not take,
# because they belong to the front end rather than to the runtime they happen to
# live in. Each entry is `<source-path>=<destination-module>`; the file's package
# declaration is rewritten to the destination's, and any resulting same-package
# import is harmless.
#
# ModuleLinker is the cross-module type-checking rule -- symbol table, coherence,
# orphan and visibility gates, FQN rewriting. It sits in pontif-runtime only
# because that is where the file loader that calls it lives. Without it `requires`
# does not resolve at all, so "keep the type system" means taking it.
# NamespaceAssembler is the sibling policy: several sources declaring one module
# name fold into one module.
GRAFT='
pontif-runtime/src/main/java/sibarum/pontif/runtime/module/ModuleLinker.java=pontif-ir
pontif-runtime/src/main/java/sibarum/pontif/runtime/module/NamespaceAssembler.java=pontif-ir
'

# Dropped: pontif-ast is the Truffle execution layer, pontif-runtime and
# pontif-demo sit on top of it, and gpu / builtin / cli / playground /
# supirvast are the runtime's surface area. They are simply not copied.

# Stripped: the files inside the taken modules that reach into Truffle, or
# that only exist to serve something which does. Paths are upstream's, so the
# list reads against a Pontif checkout.
STRIP='
pontif-ir/src/main/java/sibarum/pontif/ir/TruffleLowering.java
pontif-ir/src/main/java/sibarum/pontif/ir/TruffleProgram.java
pontif-core/src/main/java/sibarum/pontif/core/Pontif.java
pontif-core/src/main/java/sibarum/pontif/core/PontifLanguage.java
pontif-core/src/main/java/sibarum/pontif/core/PontifNode.java
pontif-core/src/main/java/sibarum/pontif/core/PontifRootNode.java
pontif-core/src/main/java/sibarum/pontif/core/Resolver.java
pontif-core/src/main/java/sibarum/pontif/core/types/Rule.java
pontif-core/src/main/java/sibarum/pontif/core/types/RuleEngine.java
pontif-ir/src/test/java/sibarum/pontif/ir/TruffleCoverageTest.java
pontif-ir/src/test/java/sibarum/pontif/ir/TruffleExecutionTest.java
pontif-ir/src/test/java/sibarum/pontif/ir/TruffleLambdaTest.java
'

# Patched: IrMatchTest and IrRecordTest run every case through both engines
# and assert they agree. Deleting them would throw away twenty-nine test
# methods to get rid of one of the two arms, so instead a patch removes the
# Truffle arm and leaves the interpreter one. These are the only hand-authored
# edits in f, which is why they are patches -- when upstream touches those
# files, `git apply` says so.

# --- 0. is the source actually Pontif? ------------------------------------

[ -d "$SRC" ] || die "no such directory: $SRC"
grep -q '<groupId>sibarum.pontif</groupId>' "$SRC/pom.xml" 2>/dev/null \
    || die "$SRC does not look like a Pontif checkout (no sibarum.pontif in pom.xml)"
say "f: source  $SRC"
say "f: dest    $DEST"
if [ -d "$SRC/.git" ]; then
    say "f: upstream $(git -C "$SRC" rev-parse --short HEAD), $(git -C "$SRC" rev-list --count HEAD) commits"
fi

# --- 1. copy the taken modules --------------------------------------------

for m in $TAKE; do
    [ -d "$SRC/pontif-$m" ] || die "upstream module missing: pontif-$m"
    rm -rf "$DEST/cardinal-$m" "$DEST/pontif-$m"
    mkdir -p "$DEST/pontif-$m"
    cp -R "$SRC/pontif-$m/src" "$DEST/pontif-$m/src"
    cp "$SRC/pontif-$m/pom.xml" "$DEST/pontif-$m/pom.xml"
done

g=0
for entry in $GRAFT; do
    src=${entry%=*}
    into=${entry#*=}
    [ -f "$SRC/$src" ] || die "graft source missing: $src (upstream moved it?)"
    dstdir="$DEST/$into/src/main/java/sibarum/pontif/${into#pontif-}"
    [ -d "$dstdir" ] || die "graft destination missing: $dstdir"
    sed "1s|^package .*|package sibarum.pontif.${into#pontif-};|" "$SRC/$src" \
        > "$dstdir/$(basename "$src")"
    g=$((g + 1))
done

# Upstream's working tree mixes CRLF and LF file by file. Normalise to LF so
# that f is deterministic and the patches below cannot fail on line endings;
# .gitattributes stores LF either way.
find "$DEST"/pontif-* -type f -exec sed -i 's/\r$//' {} +

say "f: copied 7 modules, grafted $g files"

# --- 2. strip the files that reach into Truffle ---------------------------

n=0
for f in $STRIP; do
    [ -f "$DEST/$f" ] || die "expected to delete $f, but it is not there (upstream moved it?)"
    rm "$DEST/$f"
    n=$((n + 1))
done
say "f: stripped $n files"

p=0
for patch in "$HERE"/patches/*.patch; do
    [ -f "$patch" ] || continue
    git -C "$DEST" apply --whitespace=nowarn "$patch" \
        || die "patch does not apply: $(basename "$patch") -- upstream changed the file it edits"
    p=$((p + 1))
done
say "f: applied $p patches"

# --- 3. rename sibarum.pontif -> sibarum.cardinal -------------------------
#
# Identical fully-qualified class names on one classpath is the hard conflict
# -- the loader picks one and says nothing -- so this is the requirement, not
# cosmetics. It is safe to do mechanically: no file in a taken module names
# its own package in a string literal, and no taken module ships resources.

for m in $TAKE; do
    mv "$DEST/pontif-$m/src/main/java/sibarum/pontif" "$DEST/pontif-$m/src/main/java/sibarum/cardinal"
    if [ -d "$DEST/pontif-$m/src/test/java/sibarum/pontif" ]; then
        mv "$DEST/pontif-$m/src/test/java/sibarum/pontif" "$DEST/pontif-$m/src/test/java/sibarum/cardinal"
    fi
    mv "$DEST/pontif-$m" "$DEST/cardinal-$m"
done

# Class names travel with the packages: CardinalParser parses Cardinal.
find "$DEST"/cardinal-* -name '*Pontif*' | while read -r f; do
    mv "$f" "$(dirname "$f")/$(basename "$f" | sed 's/Pontif/Cardinal/')"
done

# The textual half. `pontif` also names the language's own module namespace in
# a handful of string literals (`requires pontif.core.{Stream}`) -- that is
# Cardinal's namespace now, so the same substitution is the right one there.
find "$DEST"/cardinal-* \( -name '*.java' -o -name 'pom.xml' \) -exec \
    sed -i 's/Pontif/Cardinal/g; s/pontif/cardinal/g' {} +

# --- 4. poms --------------------------------------------------------------
#
# The module poms are transformed, not regenerated, so an upstream dependency
# change shows up here instead of being silently dropped. Two dependencies go:
# pontif-ast (the module Cardinal does not take) and truffle-api (the thing
# the fork exists to be rid of).

drop_dep() {
    awk -v art="$2" '
        /<dependency>/ && !inblk { buf = $0 "\n"; inblk = 1; next }
        inblk {
            buf = buf $0 "\n"
            if ($0 ~ /<\/dependency>/) {
                inblk = 0
                if (buf !~ ("<artifactId>" art "</artifactId>")) printf "%s", buf
                buf = ""
            }
            next
        }
        { print }
    ' "$1" > "$1.f" && mv "$1.f" "$1"
}

grep -q '<artifactId>cardinal-ast</artifactId>' "$DEST/cardinal-ir/pom.xml" \
    || die "cardinal-ir no longer depends on the ast module -- f is out of date"
drop_dep "$DEST/cardinal-ir/pom.xml" cardinal-ast

grep -q '<artifactId>truffle-api</artifactId>' "$DEST/cardinal-core/pom.xml" \
    || die "cardinal-core no longer depends on truffle-api -- f is out of date"
drop_dep "$DEST/cardinal-core/pom.xml" truffle-api

# Upstream's aggregator is `pontif-framework`; Cardinal's is just `cardinal`.
sed -i 's|<artifactId>cardinal-framework</artifactId>|<artifactId>cardinal</artifactId>|' \
    "$DEST"/cardinal-*/pom.xml

# The root pom is Cardinal's own -- there is nothing to transform, since
# upstream's aggregates nineteen modules and pins Truffle, picocli and dasum.
{
    cat <<'POM'
<?xml version="1.0" encoding="UTF-8"?>
<!-- Generated by tools/fork.sh. Edit the script, not this file. -->
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>sibarum.cardinal</groupId>
    <artifactId>cardinal</artifactId>
    <version>1.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
POM
    for m in $TAKE $OWN; do printf '        <module>cardinal-%s</module>\n' "$m"; done
    cat <<'POM'
    </modules>

    <properties>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <junit.version>5.11.3</junit.version>
        <!-- Pinned for the same reason upstream pins it: Maven's default
             binding drifts, and a build that resolves a different compiler
             plugin every few months is not a reproducible transform. -->
        <maven-compiler-plugin.version>3.13.0</maven-compiler-plugin.version>
    </properties>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>${maven-compiler-plugin.version}</version>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.junit.jupiter</groupId>
                <artifactId>junit-jupiter</artifactId>
                <version>${junit.version}</version>
                <scope>test</scope>
            </dependency>
POM
    for m in $TAKE $OWN; do
        printf '            <dependency>\n'
        printf '                <groupId>sibarum.cardinal</groupId>\n'
        printf '                <artifactId>cardinal-%s</artifactId>\n' "$m"
        printf '                <version>${project.version}</version>\n'
        printf '            </dependency>\n'
    done
    cat <<'POM'
        </dependencies>
    </dependencyManagement>

</project>
POM
} > "$DEST/pom.xml"
say "f: wrote pom.xml, transformed 7 module poms"

# --- 5. verify ------------------------------------------------------------
#
# Two of the things the README says are owed a check, checked here because
# they are properties of the transform rather than of the code.

fail=0
gen=""
for m in $TAKE; do gen="$gen $DEST/cardinal-$m"; done

# No Truffle anywhere, generated or owned -- this is the whole point of the fork.
left=$(grep -rl 'com\.oracle\.truffle' $gen "$DEST"/cardinal-*/pom.xml 2>/dev/null || true)
for m in $OWN; do
    [ -d "$DEST/cardinal-$m/src" ] || continue
    more=$(grep -rl 'com\.oracle\.truffle' "$DEST/cardinal-$m/src" 2>/dev/null || true)
    left="$left${more:+
$more}"
done
if [ -n "$(printf '%s' "$left" | tr -d '[:space:]')" ]; then
    say "f: FAIL -- Truffle survives in:"; printf '%s\n' "$left" | sed 's/^/      /'; fail=1
fi

# No 'pontif' in the generated tree. Owned modules are exempt: hand-written
# Cardinal code is allowed to name its upstream in a comment.
left=$(grep -ril 'pontif' $gen 2>/dev/null || true)
if [ -n "$left" ]; then
    say "f: FAIL -- 'pontif' survives in:"; printf '%s\n' "$left" | sed 's/^/      /'; fail=1
fi

# Owned modules must exist -- the reactor lists them, so a missing one is a
# build failure with a worse error message than this one.
for m in $OWN; do
    [ -f "$DEST/cardinal-$m/pom.xml" ] \
        || { say "f: FAIL -- owned module cardinal-$m has no pom.xml"; fail=1; }
done

[ "$fail" -eq 0 ] || die "verification failed"

# Record what this tree was generated from, so that "is the fork current?" is a
# question with an answer rather than a guess.
if [ -d "$SRC/.git" ]; then
    {
        printf '# Written by tools/fork.sh. The Pontif revision this tree was generated from.\n'
        printf 'commit  %s\n' "$(git -C "$SRC" rev-parse HEAD)"
        printf 'count   %s\n' "$(git -C "$SRC" rev-list --count HEAD)"
        printf 'subject %s\n' "$(git -C "$SRC" log -1 --format=%s)"
        printf 'date    %s\n' "$(git -C "$SRC" log -1 --format=%cI)"
    } > "$HERE/UPSTREAM"
fi

files=$(find $gen -name '*.java' | wc -l | tr -d ' ')
main=$(find $gen -path '*/src/main/*' -name '*.java' | wc -l | tr -d ' ')
lines=$(find $gen -name '*.java' -exec cat {} + | wc -l | tr -d ' ')
say "f: ok -- $files java files ($main main, $((files - main)) test), $lines lines, no Truffle, no pontif"
for m in $OWN; do
    own=$(find "$DEST/cardinal-$m/src" -name '*.java' 2>/dev/null | wc -l | tr -d ' ')
    say "f: cardinal-$m is Cardinal's own ($own java files), left untouched"
done
