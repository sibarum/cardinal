# Cardinal

A fork of [Pontif](https://github.com/sibarum/pontif-framework) that stops before
Truffle.

Cardinal keeps the syntax, the type inference, the IR and the notary. It drops the
Truffle execution layer. What is left is a front end that reads a typed,
pattern-matched, refinement-checked program and produces an IR — and **the IR and
the service descriptor are isomorphic.**

That is the whole idea. A description of what a service can do is normally a
second artifact: an OpenAPI document, a `.proto` file, a hand-written table of
command signatures. It shadows the real types, drifts from them, and is believed
because nobody checks.

Isomorphic is a stronger and more useful claim than "the IR *is* the descriptor",
which is only a slogan. It says there is a structure-preserving map both ways: the
descriptor is the interface in transit rather than a document written about it, and
anything received can be turned back into a real IR — one the notary can check for
itself. A consumer is not trusting a description. It is reconstituting a typed
structure and re-proving the claims locally.

It also buys two things a slogan cannot:

- **Encoding independence.** The descriptor can be JSON, or a typed-header table,
  or something else later, without the IR caring. The same "written form is the
  read form" law that governs values, one level up.
- **A test.** An isomorphism is checkable: serialise, read back, compare. "Is"
  has nothing to check.

The map is with the IR's *interface surface* — the types, the signatures, the
discharged claims — not with every field the IR carries. Source spans, internal
identifiers and anything left over from lowering are not part of it. Defining that
boundary precisely is a prerequisite, because it is what the isomorphism is an
isomorphism *with*.

## Why Truffle comes off cleanly

The strongest evidence is not a measurement: **Pontif already has two front ends
over one IR.** `PontifParser` reads the surface language and `SexprParser` reads
an S-expression reference syntax, with `compile` and `compileSexpr` on the same
compiler. A front end that is not the primary one is a maintained, first-class
arrangement upstream — so the seam Cardinal wants is deliberate, not inferred.

The measurements agree. Against `pontif-framework` at 647 commits (HEAD
2026-08-21):

| module | java files | files importing `com.oracle.truffle` |
| --- | --- | --- |
| `pontif-parser` | 11 | 0 |
| `pontif-predicates` | 6 | 0 |
| `pontif-conservation` | 11 | 0 |
| `pontif-receipts` | 24 | 0 |
| `pontif-runtime` | 32 | 0 |
| `pontif-ir` | 69 | 2 |
| `pontif-core` | 60 | 5 |
| `pontif-ast` | 32 | **21** |

Note that `pontif-runtime` imports no Truffle at all — the execution coupling
lives in `pontif-ast`, a tree of nodes with virtual frames. "Stop short of the
runtime" therefore means, precisely: drop the AST lowering.

And the coupling from the IR to it is two files, `TruffleLowering.java` and
`TruffleProgram.java`, which *are* the lowering step. Delete them and the IR
stands alone. The five in `core` — `Pontif`, `PontifLanguage`, `PontifNode`,
`PontifRootNode` and `Resolver` — all go. The first four are language
registration and the tree-node base class. `Resolver` was expected to argue
back, since it allocates frame slots and so looked like something the front end
might want for scoping; it does not. Its only callers are the AST nodes, two of
those four registration classes, and `TruffleLowering` — every one of them
leaving.

`PontifNode` takes two more with it: `core/types/Rule` and
`core/types/RuleEngine`, a typing-rule dispatcher keyed on AST node classes.
Nothing Cardinal keeps calls them; upstream, only `pontif-demo` does.

**Taken:** `parser`, `ir`, `core`, `predicates`, `conservation`, `receipts`,
`defaults` — 183 main files (~40,100 lines) plus 67 test files.
**Dropped:** `ast` and `runtime`, `gpu`, the builtin libraries, the CLI, the
playground.
**Stripped:** nine main files — the two lowering files and the seven above —
and three Truffle-only test classes.
**Grafted:** two files out of `runtime`, for the reason below.

### `pontif-runtime` is not the runtime

It is worth being exact about this, because the name misleads and an earlier
draft of this document was misled by it. `pontif-runtime` imports no Truffle;
it is the layer that assembles a compiler and drives it — module loading,
project discovery, the extension mechanism, the report renderers. Its 32 main
files sit over exactly the seven modules Cardinal takes.

Cardinal does not want the assembly, because it is Pontif's: a standard library
of extensions, a conductor, an event journal. It builds its own in
`cardinal-api`. But two of those files are not assembly at all —
`ModuleLinker` is the cross-module type-checking rule (symbol table, coherence,
orphan and visibility gates, FQN rewriting) and `NamespaceAssembler` is the
policy that several sources declaring one module name fold into one. They live
in `runtime` only because that is where the file loader that calls them lives.
Without `ModuleLinker`, `requires` does not resolve at all, so keeping the type
system means keeping it. `f` grafts both into `cardinal-ir`.

> Measured against `pontif-framework`. There is a `pontif-framework-2` on the
> same disk which is 138 commits behind and a month cold; an earlier draft of
> this document took its numbers from there. Re-check the tree before trusting
> any figure above.

## What does not exist yet

Nothing in `pontif-ir` serialises the IR. There is no descriptor module, no
reader, no round-trip.

So the isomorphism is the work, not a repackaging of something already present.
That is worth knowing before starting: the fork is mechanical, and the actual
build is the descriptor and its inverse.

### It is Truffle that comes off, not execution

The Truffle lowering is not, however, the only thing that consumes the IR
structurally. `pontif-ir` also holds `IrInterpreter` — 2,308 lines that
evaluate the IR directly, in plain Java, importing no Truffle at all. It came
across with the module, so Cardinal has an execution engine whether or not the
headline says so. "An artifact with no execution layer underneath it" was the
wrong way to put it; **Truffle-free** is the accurate claim, and it is the one
that matters, since Truffle is what would have had to be on a consumer's
classpath.

Two things make that fine rather than embarrassing. It is a leaf: in main
source only `Closure` refers to it, so removing it later would be a deletion
rather than a disentangling. And the runner design below already needs it —
"local evaluation is always available, so pushdown is only ever an
optimisation" is a claim about having a local evaluator, and this is the local
evaluator. Keeping it is a choice, now a deliberate one.

It is a choice with a boundary, though. `cardinal-api`, the surface an embedder
integrates against, does not use it and does not expose it: a context parses,
types and proves, and stops. The interpreter is in the tree because the module
it lives in came across whole, not because anything Cardinal offers runs
programs.

## Coexistence

Cardinal and Pontif must be able to sit in one runtime without conflicting. That
assumption is load-bearing — a service that speaks descriptors while a Pontif
program executes alongside it is the point — so it is worth stating what it
actually requires. Read off the live tree:

| what could collide | state upstream | what Cardinal must do |
| --- | --- | --- |
| Java packages | everything is `sibarum.pontif.*` (316 files) | rename to `sibarum.cardinal.*` |
| Maven coordinates | `sibarum.pontif:pontif-framework` | own groupId and artifactIds |
| JPMS module names | no `module-info.java` anywhere | nothing |
| Truffle language id | `@TruffleLanguage.Registration(id = PontifLanguage.ID)` | nothing — the registration is dropped |
| `META-INF/services` | only in the builtin and gpu modules | nothing — those are dropped |

Identical fully-qualified class names on one classpath is the hard conflict: the
loader picks one and says nothing. The package rename is not cosmetic, it is the
requirement.

It is also safe to do mechanically. No file in any module Cardinal takes names
its own Java package in a string literal, none of the seven ships a resource of
any kind — they are `.java` and a `pom.xml` and nothing else — and the only
service-loader entries register runtime extensions in modules that are being
dropped. So the rename is a path-and-declaration transform with nothing hiding
behind reflection.

The word `pontif` does survive in string literals, but as the *language's* own
module namespace rather than the Java one: `requires pontif.core.{Stream}` in
an error message, a `pontif.events/StdOut` in a test. Those are Cardinal's
namespace now and the same substitution is right for them, but it is a second
thing being renamed, and it would not have been safe to assume so.

And dropping the language registration is what makes coexistence *free* rather
than negotiated. Cardinal registers no language, claims no id, and answers to no
file extension. There is nothing for a polyglot context to arbitrate.

None of that has to be taken on trust. `tools/coexist.sh` builds both projects,
puts every jar from each on one classpath, and asserts the three things that
would break: no class file name occurs in both, one loader resolves a parser
from each project as distinct classes, and Cardinal contributes no language
registration. It runs green over 24 jars and 1,030 classes.

### Keep the divergence a transform, not a pile of edits

The benefit worth protecting is being able to take upstream work when it suits
and ignore it when it does not — and that decays fast if the fork becomes a set
of hand edits nobody can replay.

So the fork is a function: **Cardinal = f(Pontif)**, where `f` renames the
packages, drops the modules, deletes the nine files, grafts two, and patches
three. `f` lives in the repository as [`tools/fork.sh`](tools/fork.sh).
Adopting an upstream change is `f` applied to a patch rather than a manual
reconciliation, and the fork stays mergeable for as long as the transform stays
accurate. When `f` stops applying cleanly, that is information — it means
upstream moved something structural, and it is better to learn that from a
failing script than from a silent merge.

Which is why `f` fails loudly rather than doing its best. Every file it expects
to delete must be there, every graft source must be where it was, every patch
must apply, `cardinal-ir` must still depend on the module being dropped and
`cardinal-core` on `truffle-api`, and the verification at the end is fatal.
Each of those is a place where upstream could move something structural without
the result looking wrong.

Cardinal's own code lives in modules `f` does not generate. It lists them in
the reactor and otherwise leaves them alone, and they may depend on the
generated modules while nothing generated depends on them. There is one so far,
`cardinal-api`. Keeping that boundary sharp is what stops the transform
decaying: if hand-written code and generated code shared a module, every
upstream adoption would be a merge.

## The fork, as run

```
tools/fork.sh ../pontif-framework
```

Against `pontif-framework` at `fbd3c89` (647 commits), `f` reports:

```
f: copied 7 modules, grafted 2 files
f: stripped 12 files
f: applied 3 patches
f: wrote pom.xml, transformed 7 module poms
f: ok -- 240 java files (176 main, 64 test), 51679 lines, no Truffle, no pontif
f: cardinal-api is Cardinal's own (13 java files), left untouched
```

leaving `cardinal-core`, `-predicates`, `-defaults`, `-ir`, `-parser`,
`-conservation` and `-receipts` under `sibarum.cardinal:cardinal`, with
`cardinal-api` alongside them as the one hand-written module. Everything else
outside `tools/` is generated; edit the script, not the tree.
[`tools/UPSTREAM`](tools/UPSTREAM) records the revision the tree came from, so
"is the fork current?" has an answer rather than a guess.

`mvn test` is green: **727 generated tests plus 24 of Cardinal's own, no
failures.** Upstream's seven modules carry 779, and the 52 that did not come
across are exactly the Truffle execution
arms — three test classes deleted outright, and ten `truffle_*` methods removed
by two of the patches, against `IrMatchTest` and `IrRecordTest`, which run every case
through both engines and assert the two agree. Those two files are patched
rather than deleted because deleting them to be rid of one arm would have cost
twenty-nine test methods.

Two things about the transform were only claims before it ran:

- **The rename really is mechanical** — move `sibarum/pontif` to
  `sibarum/cardinal`, rename the four surviving `Pontif*` classes and thirteen
  test classes, substitute the text. No reflection, no resources, no service
  loaders. It renames two things rather than one, though, per the caveat above.
- **Line endings are not incidental.** Upstream's working tree mixes CRLF and
  LF file by file, which would have made the patches fail for reasons with
  nothing to do with the code. `f` normalises to LF before patching.

## Why a fork and not a module upstream

The alternative was real and was considered: since a front end over the IR is
already a supported shape upstream and no serialisation exists, an IR ↔ descriptor
module could have lived inside Pontif and kept one tree.

The fork wins on three things a module cannot give:

- **Divergence on purpose.** Cardinal can change the syntax, the type vocabulary
  or the IR's interface surface when the descriptor calls for it, without owing
  Pontif a migration.
- **No shared blast radius.** Neither can break the other. A change here cannot
  fail a Pontif build, and upstream churn cannot fail a service descriptor.
- **No execution layer at all.** Truffle comes off `core`, the lowering is
  deleted, and the artifact is a front end and an IR with nothing underneath it.
  A module would have carried the runtime's dependencies wherever it went.

The cost is a 40,000-line copy to keep in sync, which is what the transform above
is for.

## One syntax, over anything structured

The syntax is a query and projection language before it is anything else.
Destructuring, refinement patterns and the producer arrow are the same three
moves whether the thing underneath is a JSON document, a CSV file, a SQL result
set, an LDAP directory, an XML tree, or a filesystem.

That is a strong claim, so it is worth saying what it rests on. It has already
been demonstrated at small scale in MainFrame, where three unrelated backends —
a live directory walk, a saved index file, and a filesystem search — produce one
row shape and are therefore filtered, sorted and projected by identical
expressions, with a test asserting their columns match. SQL, LDAP and XML are the
same claim with larger backends behind it.

### JSON owns two of the three brackets

Cardinal's value syntax must stay JSON-compatible, in this precise sense:

> **Any JSON document is already a Cardinal expression.**

Records are written `{"name": value}` with the key quoted, lists are `[a, b]`,
and the punctuation is JSON's punctuation. Cardinal's own written form is JSON
*plus* the literals JSON lacks — sizes, moments, spans, typed text — each sitting
in value position, where JSON would have a number or a string. They extend the
grammar; they do not collide with it.

The relation runs one way only. A Cardinal document that uses those extra
literals is not a JSON document, and no amount of care makes it one. That price
is paid once per construct added, which is the real budget: not punctuation, but
how much of the written form stays inside JSON's grammar. It is why type
information belongs in a header — a header of annotated strings keeps a whole
document JSON-parseable, while an inline `4mb` does not.

The consequence for syntax is not a matter of taste:

- `{}` and `[]` are **spoken for** in value position.
- `()` is **available** — JSON has no parentheses at all.
- Postfix and infix forms are **available** — JSON has no operators.

Types live in type position and values live in value position, and a
recursive-descent parser always knows which it is reading. `{"a": [1, 2]}` is
unambiguously value context; a refinement after a declaration's `:` is
unambiguously type context. That coexistence is the first thing the parser fork
has to demonstrate rather than assume.

## Runners

A runner is what makes a query mean something. It supplies the data, it supplies
the scope, and **it owns the semantics.**

This resolves what looks at first like a fatal objection. SQL's `NULL` is
three-valued: `NULL = NULL` is unknown, so `WHERE x = NULL` matches nothing.
Cardinal's absent value compares equal to itself. Identical query text would
therefore give different answers depending on which backend ran it — apparently
breaking the interchangeability the whole design promises.

It does not, because asking for SQL is asking for SQL's semantics. The syntax is
notation. The runner is meaning. This is how a `LINQ` provider works: one
expression tree, each provider owning its own translation.

A runner may also **inject symbols into scope.** This is the interesting part
rather than a footnote. A geospatial runner can put `haversine` in scope, and a
filter can then use it — whereupon the runner decides whether to push it down to
a backend that has `ST_Distance`, or to stream rows and evaluate it locally.
Same query text, same answer, different plan. That is the thing every ORM does
badly.

Injecting symbols is the half a front end can actually do, and it is the half
that exists: a `Runner` contributes modules of ordinary Cardinal declarations,
and a program that `requires` one type-checks against it and has its claims
discharged against it like any other code. Deciding the plan is the runner's
own business and happens outside Cardinal entirely.

### Pushdown is a cost model, not a feature

Local evaluation is always available, so pushdown is only ever an optimisation.
MainFrame already works this way at small scale: a search runs against a saved
index when there is one and walks the disk when there is not, and the index is
"an optimisation, never a separate world." Scale the backend up and the sentence
still holds.

**Partial pushdown is where correctness dies.** Send some predicates to the
server and keep others local, and if the two engines disagree about null
handling, collation, or integer division, splitting a conjunction silently
changes the result. Silently is the operative word: nothing fails, the answer is
just wrong.

Which gives the notary a concrete job, and a better one than "the compiler does
not lie" in the abstract:

> **Prove that a pushdown is faithful, or refuse to split.**

A runner declares what it can evaluate and under what semantics. The notary
discharges the obligation that a given split preserves meaning. When it cannot,
the predicate stays local and the query is slower rather than wrong. That is the
argument for carrying the refinement engine into the fork instead of treating it
as research overhead.

Half of that is built. A runner's `Capability` declares the symbols it can
evaluate and whether its semantics are Cardinal's, and a context can be asked
which runner claims a symbol. **Nothing verifies the declaration** — the
obligation that a split preserves meaning is not discharged by anything today,
so `Capability` is a claim a planner may read rather than a proof it may rely
on. Until that obligation exists, a `FOREIGN` semantics is a standing reason
not to split a conjunction. Saying so in the type rather than leaving it
implied is the point of having the type.

## Setting up a context

The embedding surface is `cardinal-api`, Cardinal's own module. A context is a
configured front end: a language definition, a set of simplifier rules, and the
runners whose symbols are in scope.

```java
CardinalContext cardinal = Cardinal.context()
        .runner(new FilesystemRunner())
        .build();

switch (cardinal.compile(Source.of("query.cdl", text))) {
    case Compilation.Checked ok  -> describe(ok.ir(), ok.evidence());
    case Compilation.Rejected no -> no.diagnostics().forEach(d -> log(d.render()));
}
```

Four properties are deliberate.

**Nothing is executed.** A context parses, links, infers types, checks
refinements and discharges claims; what comes out is a typed IR and the
notary's evidence for it. There is no `run`, and there is no `IrInterpreter`
anywhere in `cardinal-api`.

**The notary's work is returned, not just enforced.** Upstream drafts a receipt
graph, uses it to gate the compile, and throws it away. A checked compilation
here carries the graph and a verdict per obligation, because that is what a
consumer re-proves a received descriptor against. Where the notary could not
reach a verdict at all it says so, in `Evidence.abstentions()`, so an
abstention is distinguishable from a clean bill.

**Bad input is data; bad setup is an exception.** A program that will not
compile comes back as `Compilation.Rejected` carrying diagnostics with a phase
and a position — never a thrown exception, never a string you have to parse. A
runner contributing source that does not parse, or two runners claiming one
module name, throws at `build()`, because that is a bug in the embedding rather
than something a user did.

**Scope belongs to the context, not the process.** Upstream resolves
`requires` against a process-global registry of compiler-provided modules,
which means two embeddings with different vocabularies would overwrite one
another. Here the module universe is a field, so a context with a filesystem
runner and a context with a SQL runner are alive at the same time, each able to
compile its own programs and neither able to compile the other's. That is not
tidiness: it is what the runner design assumes, and the fork's whole coexistence
argument applied one level in.

Contexts are immutable and safe to share. Building parses every contributed
module once, so build one and reuse it — that is the expensive part.

## Seams still open

These are decisions, not bugs. They should be settled before the syntax is
forked, because each one constrains it.

**XML has three namespaces on one node.** In `<a href="x">text</a>`, attributes,
child elements and text content are distinct spaces — which is why XPath needs
`@href`, `text()` and `child::`. A record has one namespace of keys. A mapping
exists (attributes into a reserved field, text into another), but this is the one
place where "everything is a record" costs something real rather than being free.
Repeated sibling elements become a list, and sibling order survives because
records are ordered.

**`@` collides with XPath.** Pontif's refinement subject is `@`; XPath's
attribute selector is `@`. In `[Int:@>0]` it is the value under test, in
`//a[@href]` it is an attribute. Anyone arriving from XPath will read one as the
other, and XML is precisely the case where that muscle memory would otherwise
transfer. Keep it, move it, or accept the collision — but deliberately.

**Multi-valued and nested data are already fine.** LDAP attributes hold several
values, which is a list-valued cell. Mongo documents nest, which records do
natively. Neither needs anything new.

## How this document is enforced

Constraints that only live in prose stop being true quietly. The precedent worth
copying is MainFrame's `JsonSubsetTest`, which reads every shape JSON can take
through two different readers and asserts they agree — turning "JSON is a subset"
from an intention into something that fails the build.

Mechanically checkable, and therefore owed a test:

- **the isomorphism**: for every program, the descriptor read back yields an IR
  whose interface surface is indistinguishable from the original, and the
  notary reaches the same verdict on both. This is the load-bearing one — it is
  what licenses a consumer to trust a received descriptor as much as a locally
  compiled one.
- any JSON document parses as Cardinal source, to the same value and the same type
- the written form of a value reads back as that identical value
- a runner's declared pushdown capability matches what it actually evaluates

Already checked, by something that runs:

- nothing Cardinal ships imports Truffle, and no `pontif` survives the
  rename — `f` asserts both, fatally, on every run
- Cardinal and Pontif coexist on one classpath — `tools/coexist.sh`
- the front end still works after the transform — 727 tests, which is upstream's
  whole suite for these modules bar the Truffle arms
- two contexts in one JVM have separate scopes: each compiles its own
  programs and neither can compile the other's — `RunnerScopeTest`
- a runner's modules are in scope only where required, and an unused runner
  injects nothing — `RunnerScopeTest`

Judgement, and therefore owed a decision recorded here:

- where the interface surface ends and the IR's private business begins
- the XML node mapping
- whether `@` stays
- which of Pontif's type-level syntax survives contact with JSON's brackets

## Where MainFrame fits

[MainFrame](https://github.com/sibarum/mainframe) becomes one runner among
several — the filesystem one — rather than the thing being designed. Its typed
pipes, its command signatures and its three type vocabularies collapse into
Cardinal's one. `commands`, which currently emits a descriptor written by hand,
starts emitting a projection of the IR — which, if the isomorphism holds, is the
same information rather than a second copy of it.

Concretely, that is an implementation of `Runner` contributing a module that
declares the row shape and the filesystem vocabulary, registered on a context.
Its index-versus-disk-walk choice is exactly the `Capability` distinction, and
it is the case that will show whether the declaration is shaped right before
anything tries to prove things about it.

## Licence

Cardinal is a fork of the Pontif Framework, Copyright 2026 James Watkins and the
Pontif contributors, used under the Apache License 2.0. See `LICENSE` and
`NOTICE`.

Cardinal is dual-licensed the same way Pontif is: source code under Apache-2.0,
documentation -- including this file -- under CC BY 4.0. See `LICENSE` and
`LICENSE-docs`.
