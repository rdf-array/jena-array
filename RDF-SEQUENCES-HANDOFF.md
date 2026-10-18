# RDF Sequences spike (branch `rdf-sequences`, worktree `jena-sequences-wt`)

Branch point: `all-rdf` @ 744357a559 (fhircat/jena, Jena 5.0.0-SNAPSHOT).
Status: uncommitted spike. Nothing here is pushed.

## The data model

RDF terms are IRIs, blank nodes, literals, triple terms (RDF-star, already in Jena)
and, new, **sequences**: an ordered collection of terms that is itself a term.

    <s> <p> (1 2) .          # ONE triple; its object is the sequence of 1 and 2

There are no `rdf:first`/`rdf:rest` triples, `()` is the empty sequence (not
`rdf:nil`), sequences nest, and a sequence may be a subject or an object. A
sequence is concrete when its elements are; with variables inside it is a SPARQL
pattern.

| layer | what                                                                       | where |
|-------|----------------------------------------------------------------------------|-------|
| core  | `Node_Seq` (`List<Node>`), `Node.isNodeSeq()/getSeq()`, `NodeFactory.createSeqNode(...)`, `NodeVisitor.visitSeq` | `jena-core/.../graph/` |
| RIOT  | Turtle/TriG `( ... )` and N-Triples/N-Quads `( ... )` parse to a `Node_Seq`; `NodeFormatter` writes `( e1 e2 )` / `()`; `ParserProfile.createSeqNode`; checker accepts sequences as subject/object | `riot/lang/LangTurtleBase`, `LangNTuple`, `LangNQuads`, `riot/out/NodeFormatterBase`, `riot/system/{Checker,ParserProfileStd,ParserProfile}` |
| RIOT  | the pretty Turtle/TriG writer no longer abbreviates legacy first/rest ladders as `( )` (that would silently change the model on re-read); `rdf:nil` is written as `rdf:nil`, not `()` | `riot/writer/TurtleShell` (`ABBREVIATE_LEGACY_LISTS = false`) |
| SPARQL | grammar: `( e1 e2 )` is one sequence term (patterns, templates, INSERT/DELETE DATA); `()` is the empty sequence; `((element))[index]` is a **position pattern**; `expr[i]` indexes a sequence in expressions; `isSEQ(x)`, `SEQLEN(x)`, `SEQ(e1, ...)` | `jena-arq/Grammar/main.jj`, regenerated `sparql_12` and `arq` parsers (JavaCC 7.0.13) |
| SPARQL | `Node_SeqIndex` pattern term `((element))[index]`: index is a 1-based integer, a variable (bound to the position), or `*` (any position) | `sparql/core/Node_SeqIndex` |
| SPARQL | matching: the RDF-star solvers became multi-result term matchers that recurse into triple terms, sequences and position patterns (`(1 ?x)`, `((?e))[2]`, `((?e))[?i]`, `((?e))[*]`, nested) | `engine/main/solver/{SolverRX3,SolverRX4,SolverLib}` |
| SPARQL | substitution, variable collection, renaming, SSE (`(seqterm ...)`, `(seqpos elt idx)`), query/algebra serialisation, ordering (`NodeCmp`), isomorphism (`IsoAlg`), JSON results (`"type": "seq"`) | `core/Substitute`, `util/VarUtils`, `engine/Rename`, `graph/NodeTransformLib`, `sse/{Tags,ItemWriter,ItemLift,BuilderExpr}`, `util/FmtUtils`, `serializer/FmtExprSPARQL`, `util/NodeCmp`, `util/IsoAlg`, `riot/rowset/rw/RowSetWriterJSON` |
| SPARQL | functions `E_IsSeq`, `E_SeqLen`, `E_SeqAt` (`x[i]`), `E_MkSeq` (`SEQ(...)`) over `function/library/seq/SeqFunctions` | `sparql/expr/` |
| lists  | `G.rdfList`, `G.getOneOrList`, `GraphList.{members,length,get,index,indexes,isListNode,findAllLists}` accept a sequence term as "the list" (legacy ladders still work), so ShExR, manifests, property-function argument lists (`?x apf:strSplit (?s "re")`) and `list:member/index/length` work over sequences | `system/G`, `sparql/util/graph/GraphList` |
| ShEx  | `Collection` shape expression `((shapeExpr))[min, max]`: ShExC (`[m]` exactly m, `[m, n]`, `[m, *]`/`[m,]`, none = any length), ShExJ `{"type":"Collection","shapeExpr":…,"min":m,"max":n}`, ShExR `sx:Collection` + `sx:shapeExpr`/`sx:min`/`sx:max`; validation: focus node must be a sequence of acceptable length, each element validated (as a focus node of its own) against `shapeExpr`; ShExC writer; reporters | `jena-shex/shexc/shex.jj` (regenerated), `expressions/ShapeCollection`, `parser/{ParserShExC,ParserShExR}`, `jsonld/{JsonGEnum,ShexJsonldDeserializer}`, `validation/ShapeExprEval`, `writer/WriterShExC`, `calc/*Visitor*`, `reporting/*` |
| tests | `riot/lang/TestLangSequences` (TS_Lang), `sparql/engine/TestSequenceQueries` (TS_Engine), `shex/TestShexCollection` (TS_Shex) | |

### SPARQL position patterns — the `(())[i]` notation

    ?s :p (1 ?a ?b)          # exact length; positional binding
    ?s :p ((?e))[2]          # 2nd element (1-based)
    ?s :p ((?e))[?i]         # every element, ?i bound to its position
    ?s :p ((?e))[*]          # every element
    ?s :p ((((?e))[1]))[2]   # nested: first element of the second element
    BIND(?seq[2] AS ?x)      # expression form; error (unbound) when out of range
    FILTER(isSEQ(?x) && SEQLEN(?x) = 3)
    CONSTRUCT { ?s :r (?a ?b) } WHERE { ?s :p (?a ?b ?c) }

A bare `((x))` without brackets is a nested one-element sequence; the position
pattern requires the `[...]`. Parsed with a syntactic lookahead
`(( GraphNode ))[`, so `((1 2) 3)` is still a nested sequence.

### ShExC — `((t))[m, n]`

    <S1> { <p> ((@<T>))[1, 2] }       # a sequence of 1..2 nodes each satisfying <T>
    <S2> { <p> ((xsd:integer)) }      # any length (including empty) of integers
    <S3> { <p> ((xsd:integer))[2] }   # exactly two
    <S4> { <p> ((@<T>))[1, *] }       # at least one

Parsed with a syntactic lookahead for `(( shapeExpression ))`, so
`((a OR b) AND c)` is still a parenthesised expression. The remaining ambiguity is
`((x))` with nothing after it: it is a Collection, not doubly-parenthesised `x`.

## Decisions worth knowing

- `()` is the empty sequence, not `rdf:nil`. `rdf:nil` is an ordinary IRI; the
  Turtle writer prints it as `rdf:nil`.
- Legacy data with `rdf:first`/`rdf:rest` triples is untouched: it parses, queries
  and writes as plain triples. The pretty writer never abbreviates it as `( )`.
  Converting ladders to sequences is a deliberate (future) operation, not a side
  effect of a round trip.
- The SPARQL 1.0 and 1.1 parsers (`sparql_10`, `sparql_11`) were not regenerated;
  they keep the ladder semantics. The default syntax (SPARQL 1.2 / ARQ) is the
  new one. The JavaCC-based "extra" Turtle parser (`Grammar/Turtle/turtle.jj`,
  `riot/lang/extra`) was not changed either.
- Generated parser noise: the checked-in parsers were produced by a GNU `cpp`;
  Apple's `cpp` keeps comments, so the regeneration used a small wrapper
  (`clang -E -P -x c`, see scratch notes) to keep the diff to the real changes.
  `javacc`/`jjdoc` wrappers around `javacc-7.0.13.jar` (fetched from Maven Central,
  SHA-1 verified) are not in the tree.
- A bare collection as a triple subject with no predicate-object list (`{ (1 2) }`)
  now contributes nothing to a pattern (it used to assert list triples).
- The RDF-star solver "DATAPATH" trap is kept: only patterns with compound terms
  containing variables take the slow matcher.
- Property-function argument lists (`?x apf:strSplit (?s "re")`, `?l list:index (0 ?m)`)
  are sequence terms; `PropertyFunctionGenerator` already recognises them through
  `GraphList.isListNode`. An empty argument `()` stays the empty sequence
  (`PropFuncArg` used to turn it into `rdf:nil`), so `?l list:member ()` finds
  the empty sequence. Unbound-list lookups (`list:member`/`list:index` with an unbound
  subject) scan the graph for sequence terms, each equal sequence collected once.
- jena-core's Model API: a sequence surfaces as a `Resource` whose node is a
  `Node_Seq` (so `QuerySolution`/`ResultSetFormatter` work). `RDFList` is not
  sequence-aware.

## Not done / needs a decision (flagged)

- **Isomorphism with blank nodes inside sequences** — ARQ's `IsoAlg`/`IsoMatcher`
  handles it; jena-core's `GraphMatcher` (`Model.isIsomorphicWith`) does not
  descend into compound terms (same as for triple terms).
- **Other syntaxes**: RDF/XML (`parseType="Collection"` still produces ladders; the
  writer cannot serialise a `Node_Seq`), JSON-LD (`@list` ↔ sequence not mapped),
  TriX, RDF/JSON, RDF Thrift, RDF Protobuf, TDB1/TDB2 node encoding. Each needs an
  encoding decision.
- **SPARQL results**: JSON writer emits `{"type":"seq","value":[...]}`; the JSON
  reader, XML/CSV/TSV writers and readers do not know sequences.
- **Expressions**: no `(1 2)` sequence constructor syntax inside expressions (use
  `SEQ(1, 2)`); `ExprTripleTerm`-style `ExprSeqTerm` not added.
- **ShExJ writer**: jena-shex has no shape-expression serialiser to ShExJ, so
  `Collection` is read, not written, in ShExJ.
- **Property functions**: `?list list:member ?x` with an unbound `?list` finds
  sequences used as subjects/objects (full scan), not nested ones.
- **jena-shexmap, jena-shacl, jena-cmds, Fuseki** not rebuilt here (they are not
  in the offline reactor). SHACL reads lists via `G.rdfList`, so `sh:in`,
  `sh:or`, … over `( )` should work; untested.

## Tests that query or test for rdf:first / rdf:rest (and what to do with them)

Legend: *index* = can be expressed with `((x))[i]`, `[*]`, `SEQLEN`, or a ShEx
Collection; *structural* = tests the ladder itself, no sequence equivalent.

### SPARQL / ARQ (jena-arq)

The scripted suites (`Scripts_ARQ`, `Scripts_DAWG`, `Scripts_RefEngine`, `Scripts_TIM`,
`Scripts_SPARQL11`, `Scripts_RDFStar`) run again on this branch (the fork's own
`AbstractRunnerOfTests.prepareTests` threw "No manifest entries found" for
include-only manifests such as `testing/ARQ/manifest-arq.ttl`; the spike skips that
check when a manifest has no entries of its own). DAWG, SPARQL 1.1 and RDF-star
suites pass unchanged. What fails, and why:

| test | what it does | class |
|------|--------------|-------|
| `ARQ/PropertyFunctions/list-length-2`, `ARQ/EngineMain/filter-1` (and `TIM-` twins) | `?list list:length 3`; `?list list:index (?index "B")` with `?list` unbound; the `.srx` files expect the list to be a blank node | *index* — results are right (`( "a" "b" "c" )`, `( "A" "B" "C" ) \| 1`); the expected results files must carry the sequence |
| `ARQ/Function/localname-1`, `namespace-1` (+ `TIM-`) | `jfn:localname(?p) = "list"` / `jfn:namespace(?p) = rdf:` over data with `(1 2 3)` | *structural* — expected results include the `rdf:first`/`rdf:rest` predicates that no longer exist; re-record |
| `rdf-tests-cg/sparql11-query/property-path/pp05` (`?x rdf:rest{0,1} ?y`) | counts ladder cells | *structural*; passes only because its data is written with explicit `rdf:first` triples |
| `ARQ/Syntax/.../syntax-lists-03/04/05`, `syntax-forms-02`, `Serialization/syntax-lists-05` (`{ ( [] [] ) }`, `{ ( ( ) ) }`) | a bare collection as the only thing in a group | parses; now contributes no triples (the spike drops the empty block so the reparse check and `Query.clone` agree) |
| `sparql/util/TestList.testListTriples_2` | `GraphList.allTriples` of a list read from Turtle | *structural* — a sequence has no list triples (the other 45 `GraphList` tests pass over sequences) |
| `riot/writer/TestRiotWriterGraph.writer04/05/06` (`writer-rt-04.ttl`: `(1 2 3)`, `()`, `( :s [ :p :o ] )`) | round trip through every writer | N-Triples/N-Quads/Turtle/TriG round-trip correctly but `Model.isIsomorphicWith` (jena-core `GraphMatcher`) cannot map the blank node inside `( :s [...] )`; RDF/XML, JSON-LD, TriX, RDF/JSON, Thrift, Protobuf cannot serialise a sequence at all |
| `riot/system/TestJsonLDReadWrite.read_g01/g02/roundtrip_01/02` | JSON-LD `@list` vs Turtle `( )` | JSON-LD not mapped to sequences |
| `jena-core/.../TestList` (RDFList API), reasoner rule files (`owl*.rules`, `rdfs*.rules`) | Model-level `RDFList` and rule bodies that walk `rdf:first`/`rdf:rest` | untouched: they operate on legacy ladders built by the API or by RDF/XML; the Model API has no sequence view |

### RIOT syntax suites (jena-arq `Scripts_LangSuite`, `Scripts_LangSuiteCG`)

W3C Turtle/TriG evaluation tests whose expected N-Triples/N-Quads are ladders:
`collection_object`, `collection_subject`, `empty_collection`, `nested_collection`,
`first`, `last`, `blankNodePropertyList_containing_collection`, `turtle-subm-08`,
`turtle-subm-09`, `trig-subm-08`, `trig-subm-09`, `turtle-eval-lists-01..05`,
`RIOT/Lang/TurtleSubm/test-07`, `test-08` (each in two suites). The parser now
produces the sequence; the `.nt`/`.nq` expectations encode the old model and would
be rewritten as `<s> <p> ( ... ) .`. RDF/XML tests (`rdf11-xml`, `rrx/collections*`)
still pass: `parseType="Collection"` still builds ladders.

### ShEx (jena-shex `TS_Shex`: 2165 tests)

| test | class |
|------|-------|
| `1list0PlusDot-*_pass`, `1list0PlusIri-*_pass`, `1list1PlusIri-*_pass` (6) | the schema's `[rdf:nil] OR CLOSED { rdf:first . ; rdf:rest @<L> }` ladder shapes over data written as `( ... )`; expressible as `((.))`, `((IRI))`, `((IRI))[1, *]` — the shexTest corpus would gain Collection forms |
| `*manualList*` (data with explicit `rdf:first` triples) | still pass: ladders are still just triples |
| `TestParserShExR.roundTripAgainstShExC` for `1list0PlusDot`, `1list0PlusIri`, `1list1PlusIri` | their ShExR `.ttl` fixtures write the value `rdf:nil` as `()` inside `sx:values ( () )`; under the new model that is an empty sequence. Fixture issue (`( rdf:nil )`). |

### jena-core (not run in detail; data in `testing/wg`, `testing/ontology`, `testing/reasoners`)

OWL/RDFS test data and the rule files reference `rdf:first`/`rdf:rest` heavily
(`owl:unionOf`, `owl:oneOf`, `owl:intersectionOf` lists). They are RDF/XML or
built through the Model API, so nothing changes for them; converting OWL list
vocabulary to sequences would be a reasoning-level decision (the rules walk
ladders — *structural*).

## Test status (2026-10-18, offline reactor: iri, base, core, arq, shex)

| suite | result |
|-------|--------|
| new: `TestLangSequences` 12, `TestSequenceQueries` 23, `TestShexCollection` 14 | all pass |
| jena-core (TestPackage, 245), jena-base, jena-iri | pass |
| `TS_Lang` 2200, `TS_Query` 975, `TS_Engine` 40, `TestPathPF`, `TestStrSplit` | pass |
| `Scripts_DAWG` 496, `Scripts_SPARQL11` 466, `Scripts_RDFStar` 102 | pass |
| `Scripts_ARQ` 1111 | 4 fail: `list-length-2`, `filter-1`, `localname-1`, `namespace-1` (expectation files, see table) |
| `Scripts_TIM` 605 / `Scripts_RefEngine` 487 | the same tests through the TIM / reference engines |
| `Scripts_LangSuite` 868 / `Scripts_LangSuiteCG` 799 | 20 / 23 fail: the W3C collection evaluation tests (ladders in `.nt`/`.nq` expectations) |
| `sparql/util/TestList` 46 | 1 fail: `testListTriples_2` (structural) |
| `TS_RiotWriter` (`TestRiotWriterGraph`) | `writer04/05/06` fail: `GraphMatcher` bnodes-in-sequence; unsupported serialisations |
| `TestJsonLDReadWrite` | 4 fail: JSON-LD `@list` |
| `TS_Shex` 2165 | 7 fail: six `1list*` ladder-shape validations, `TestParserShExR.roundTripAgainstShExC` (`( () )` fixtures) |

## Running it

    JAVA_HOME=/opt/homebrew/opt/openjdk@21
    MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
    "$MVN" -o -q -pl jena-shex -am -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Drat.skip=true -Denforcer.skip=true package
    "$MVN" -o -pl jena-arq,jena-shex -am -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Drat.skip=true -Denforcer.skip=true test \
        -Dtest='TestLangSequences,TestSequenceQueries,TestShexCollection' -Dsurefire.failIfNoSpecifiedTests=false

Never `install` from this worktree: it would overwrite the 5.0.0-SNAPSHOT jars in
`~/.m2` that the main checkout resolves offline. Grammar regeneration:
`jena-arq/Grammar/grammar` and `jena-shex/shexc/shex-parser` with `javacc`,
`jjdoc` and a comment-stripping `cpp` on the PATH (see the memory note).
