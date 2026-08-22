# Cardinal

A fork of [Pontif](https://github.com/sibarum/pontif-framework) that stops before
the runtime.

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

## Why the execution layer comes off cleanly

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
stands alone. The five in `core` are `Pontif`, `PontifLanguage`, `PontifNode`,
`PontifRootNode` and `Resolver` — the first four are Truffle language
registration and drop out with it; `Resolver` is the one that will argue back,
since it appears to want frame slots for something the rest of the front end
needs.

**Taken:** `parser`, `ir`, `core`, `predicates`, `conservation`, `receipts`,
`defaults` — 183 files, about 40,100 lines.
**Dropped:** `ast` and `runtime` (64 files, ~6,400 lines), `gpu`, the builtin
libraries, the CLI, the playground.
**Stripped:** the two lowering files, and four of the five Truffle files in
`core`.

This plan rests on module-level dependency and import counts plus the
two-front-ends arrangement, not on a read of the IR or the notary internals.

> Measured against `pontif-framework`. There is a `pontif-framework-2` on the
> same disk which is 138 commits behind and a month cold; an earlier draft of
> this document took its numbers from there. Re-check the tree before trusting
> any figure above.

## What does not exist yet

Nothing in `pontif-ir` serialises the IR. There is no descriptor module, no
reader, no round-trip. The lowering to Truffle is the only thing that consumes
the IR structurally.

So the isomorphism is the work, not a repackaging of something already present.
That is worth knowing before starting: the fork is mechanical, and the actual
build is the descriptor and its inverse.

## Fork, or a module upstream?

Worth deciding rather than assuming, because the two-front-ends finding changes
the calculus.

Since a front end over the IR is already a supported shape, and no serialisation
exists, most of what Cardinal wants could be **a module inside Pontif** — an
IR ↔ descriptor pair with no Truffle in it, sitting alongside the two existing
front ends. That keeps one tree and 647 commits of upstream work flowing in.

What a fork buys that a module cannot: stripping Truffle from `core` and deleting
the lowering, so the artifact has no execution layer at all and no Truffle on the
classpath. If the goal is a descriptor library small enough to embed anywhere,
that matters. If the goal is the descriptor itself, a module gets there sooner and
without a 40,000-line copy to keep in sync.

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
- no module outside the fork boundary imports Truffle
- a runner's declared pushdown capability matches what it actually evaluates

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

## Licence

Cardinal is a fork of the Pontif Framework, Copyright 2026 James Watkins and the
Pontif contributors, used under the Apache License 2.0. See `LICENSE` and
`NOTICE`.

Cardinal is dual-licensed the same way Pontif is: source code under Apache-2.0,
documentation -- including this file -- under CC BY 4.0. See `LICENSE` and
`LICENSE-docs`.
