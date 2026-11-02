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
| SPARQL | grammar (the syntax of rdf-array's [docs/array-syntax.md](https://rdf-array.github.io/rdf-array/docs/array-syntax.html)): `( e1 e2 )` is one sequence term (patterns, templates, INSERT/DELETE DATA); `()` is the empty sequence; in a WHERE pattern a member may be a **gap** `...` or a **named gap** `...?rest`; `UNNEST(expr AS ?x AT ?i)` is a graph pattern; `expr[i]` indexes and `expr[m...n]`, `expr[m...]`, `expr[...n]` slice a sequence in expressions; `?x IN ?seq` / `NOT IN ?seq`; `isSEQ(x)`, `SEQLEN(x)`, `SEQ(e1, ...)` | `jena-arq/Grammar/main.jj`, regenerated `sparql_12` and `arq` parsers (JavaCC 7.0.13) |
| SPARQL | `Node_SeqGap` pattern term: `...` matches any run of members; `...?rest` also binds `?rest` to the run, as a sequence (the same variable twice must match equal runs) | `sparql/core/Node_SeqGap`, `lang/QueryParserBase.createSeqGapNode` |
| SPARQL | matching: the RDF-star solvers are multi-result term matchers that recurse into triple terms and sequences; a sequence pattern with gaps is matched by backtracking over the run lengths (`SolverRX3.matchMembers`), so `( ... ( "c" ?n ( ?v ?u ) ) ... )` finds a tuple anywhere | `engine/main/solver/{SolverRX3,SolverRX4,SolverLib}` |
| SPARQL | `UNNEST`: `ElementUnnest` → `OpUnnest` (`(unnest (?x ?i) expr sub)`) → `QueryIterUnnest`; one solution per member, none when the expression is unbound, errors or is not a sequence; a variable already bound keeps only the equal members (join semantics, no scope error) | `syntax/ElementUnnest`, `algebra/op/OpUnnest`, `engine/iterator/QueryIterUnnest`, `AlgebraGenerator`, `OpExecutor`, `ref/Evaluator*`, `OpVars`, `VarFinder`, `JoinClassifier`, `OpAsQuery`, the `Op`/`Element` visitors and transforms, `FormatterElement`, `sse/{WriterOp,BuilderOp}` |
| SPARQL | substitution (a named gap bound to a sequence is spliced in), variable collection, renaming, SSE (`(seqterm ...)`, `(seqgap)`, `(seqgap ?rest)`), query/algebra serialisation, ordering (`NodeCmp`), isomorphism (`IsoAlg`), JSON results (`"type": "seq"`) | `core/Substitute`, `util/VarUtils`, `engine/Rename`, `graph/NodeTransformLib`, `sse/{Tags,ItemWriter,ItemLift,BuilderExpr}`, `util/FmtUtils`, `serializer/FmtExprSPARQL`, `util/NodeCmp`, `util/IsoAlg`, `riot/rowset/rw/RowSetWriterJSON` |
| SPARQL | functions `E_IsSeq`, `E_SeqLen`, `E_SeqAt` (`x[i]`), `E_SeqSlice`/`E_SeqSliceFrom`/`E_SeqSliceTo` (`x[m...n]`, `x[m...]`, `x[...n]`: closed, 1-based, clamped; SSE `seqSlice`, `seqSliceFrom`, `seqSliceTo`), `E_InSeq`/`E_NotInSeq` (`x IN ?seq`; SSE `inSeq`, `notInSeq`), `E_MkSeq` (`SEQ(...)`) over `function/library/seq/SeqFunctions` | `sparql/expr/` |
| lists  | `G.rdfList`, `G.getOneOrList`, `GraphList.{members,length,get,index,indexes,isListNode,findAllLists}` accept a sequence term as "the list" (legacy ladders still work), so ShExR, manifests, property-function argument lists (`?x apf:strSplit (?s "re")`) and `list:member/index/length` work over sequences | `system/G`, `sparql/util/graph/GraphList` |
| ShEx  | the `LIST` shape expression (array-syntax.md §2, variant G): ShExC `LIST ( regex )` and `LIST card x`; ShExJ `{"type":"ListShape","expression":…}` with `Member`/`ListEachOf`/`ListOneOf`; ShExR `sx:ListShape`/`sx:expression`, `sx:Member`/`sx:shapeExpr`, `sx:ListEachOf`/`sx:ListOneOf`/`sx:expressions`, `sx:min`/`sx:max`; validation: the focus node must be a sequence whose members, in order, match the regular expression (`ShapeExprEval.ListMatcher`: the set of end positions, as `ends()` in rdf-array's harness, each member validated once per shape expression as a focus node of its own); ShExC writer; reporters | `jena-shex/shexc/shex.jj` (regenerated), `expressions/{ListShape,ListExpr,ListMember,ListEachOf,ListOneOf}`, `parser/{ParserShExC,ParserShExR}`, `jsonld/{JsonGEnum,ShexJsonldDeserializer}`, `validation/ShapeExprEval`, `writer/WriterShExC`, `calc/*Visitor*`, `reporting/*` |
| tests | `riot/lang/TestLangSequences` (TS_Lang), `sparql/engine/TestSequenceQueries` and `TestArraySyntaxExamples` (TS_Engine; the latter is array-syntax.md §4.1), `shex/TestShexList` (TS_Shex; §4.2 and §5), `rdfarray/TestRdfArrayServer` (TS_RdfArray) | |

### SPARQL-array: gaps, UNNEST, indexes, slices, IN

    ?s :p (1 ?a ?b)                 # exact length; positional binding
    ?s :p ( ?first ... ?last )      # a gap matches any run of members (here: 2 or more members)
    ?s :p ( ... ?x ... )            # every member
    ?s :p ( ?h ...?t )              # a named gap binds the rest, as a sequence
    ?s :p ( ...?pre ?x ... )        # SEQLEN(?pre) + 1 is ?x's position
    ?s :p ( ... ( "c" ?n ( ?v ?u ) ) ... )   # a 3-tuple anywhere in a list of tuples; gaps nest
    UNNEST(?seq AS ?x AT ?i)        # one solution per member, ?i its 1-based position; AT optional
    BIND(?seq[2] AS ?x)             # index; error (unbound) when out of range
    BIND(?seq[2...3] AS ?y)         # slices: closed, 1-based, clamped; also ?seq[2...] and ?seq[...3]
    FILTER(?x IN ?seq)              # membership; the right-hand side is a variable, SEQ(...) or an indexed one of these
    FILTER(?x IN (?seq))            # SPARQL 1.1: an expression list, ?x = ?seq
    FILTER(isSEQ(?x) && SEQLEN(?x) = 3 && ?x = SEQ(1, 2, 3))
    CONSTRUCT { ?s :r (?a ?b) } WHERE { ?s :p (?a ?b ?c) }   # no gaps in templates or data

Lexing: `...` is one token and `...?rest` another, so `( ?a ... ?b )` is a gap and
a member while `( ?a ...?b )` is a named gap; `1...` and `:a...` are a member and a
gap, `:a...b` one prefixed name; `[2...4]` is a slice, `[2:4]` a syntax error (`:4`
is a prefixed name). The old `((?e))[i]` position patterns and `Node_SeqIndex`
are gone: `((?e))[2]` is now a pattern `( ( ?e ) )` followed by a syntax error.

### ShExC — `LIST`

    <S1> { <p> LIST {1,2} @<T> }                  # one or two members, each satisfying <T>
    <S2> { <p> LIST * xsd:integer }               # any number of integers, possibly none
    <S3> { <p> LIST ( xsd:integer ; xsd:string ) }   # a pair: a regular expression over the members
    <S4> { <p> LIST ( ( xsd:string ; xsd:integer ) * ) }   # a flat list of pairs (a repeated group)
    <Tree> LIST ( xsd:string ; LIST ( @<Tree> * ) )      # recursion through nested lists

`LIST card x` is `LIST ( x card )`; a bare `LIST x` is a syntax error. Inside
`LIST ( )` an opening `(` always starts a group, so a member's first atom may not be
parenthesised (`( ( @<A> ) AND @<B> )` is an error; write `@<B> AND ( @<A> )`); a
cardinality after a parenthesised group that already has one wraps it in a
`ListEachOf` of one. A cardinality after the whole `LIST` belongs to the triple
constraint. `LIST` is a shape atom in `shapeAtom` and `inlineShapeAtom`, not after
`EXTENDS`. The writer prints `LIST ( regex )` always; a member that is an AND/OR whose
first operand would be parenthesised has its operands rotated, and `. AND ( ... )`
when all would be.

## rdf-array demo: `jena-rdfarray`

A new module with one HTTP server (`org.apache.jena.rdfarray.RdfArrayServer`, JDK
`HttpServer`, no extra dependencies) and one page, `src/main/resources/.../index.html`:
a shared Turtle-array data panel and three tabs. Turtle-array shows the parsed triples with
`seq` terms; SPARQL-array runs SELECT/ASK/CONSTRUCT over the data (results JSON carries
`"type": "seq"`); ShEx-array validates with a query map or node + shape and shows the report.
The routes copy the shex.js validation server (`-S URL` names the validation route;
`-x -d -s -n -m` preload; `--serve-n`, `-Q`, `--terse`; multipart uploads, forms, query
strings and JSON bodies; `{"type": "ParsingError", "errors": [...]}` with 400).
`jena-rdfarray/bin/rdfarray -S http://localhost:8099/validate` runs it from the build tree;
`TS_RdfArray` (23 tests) starts it on an ephemeral port and exercises every route, the
preload mode and `--serve-n`. See `jena-rdfarray/README.md`.

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
  handles it, and so does jena-core's `GraphMatcher` (`Model.isIsomorphicWith`,
  2026-10-25): it unfolds each sequence that holds a blank node, in both graphs, into
  a ladder in a private vocabulary (`urn:rdf-array:sequence#first|rest|nil`, as in
  rdf-test-suite-array) and drops the ladder cells from the mapping. Blank nodes
  inside triple terms are still not mapped.
- **Other syntaxes**: RDF/XML (`parseType="Collection"` still produces ladders; the
  writer cannot serialise a `Node_Seq`), JSON-LD (`@list` ↔ sequence not mapped),
  TriX, RDF/JSON, RDF Thrift, RDF Protobuf, TDB1/TDB2 node encoding. Each needs an
  encoding decision.
- **SPARQL results**: JSON `{"type":"seq","value":[...]}` and XML `<seq>` are written
  and read, TSV reads `( … )`, and `ResultSetCompare` compares sequences (2026-10-25,
  found by rdf-tests-contrib).
- **Expressions**: no `(1 2)` sequence constructor syntax inside expressions (use
  `SEQ(1, 2)`); `ExprTripleTerm`-style `ExprSeqTerm` not added.
- **ShExJ and ShExR writers**: jena-shex has no shape-expression serialiser to ShExJ
  (`ShexJsonLdSerializer` is a reflection stub) nor to ShExR, so `ListShape` is read,
  not written, in those syntaxes.
- **Property functions**: `?list list:member ?x` with an unbound `?list` finds
  sequences used as subjects/objects (full scan), not nested ones.
- **jena-shexmap, jena-cmds, Fuseki** not rebuilt here (they are not in the offline
  reactor). jena-shacl is: see "SHACL-array" below.

## Tests that query or test for rdf:first / rdf:rest (and what to do with them)

Legend: *index* = can be expressed with a gap pattern, `UNNEST`, `SEQLEN`, or a ShEx
`LIST`; *structural* = tests the ladder itself, no sequence equivalent.

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
| `riot/writer/TestRiotWriterGraph.writer04/05/06`, `TestRiotWriterDataset.writer03` (`writer-rt-04.ttl`: `(1 2 3)`, `()`, `( :s [ :p :o ] )`) | round trip through every writer | N-Triples, N-Quads, Turtle and TriG round-trip (since 2026-10-25: `GraphMatcher` maps blank nodes inside sequences, and the pretty Turtle/TriG writer no longer writes a blank node used only inside a sequence as a free-standing `[ … ]` while labelling it in the sequence, `TurtleShell.findBNodesSyntax1`); RDF/XML, JSON-LD, TriX, RDF/JSON, Thrift, Protobuf cannot serialise a sequence at all (53 errors) |
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
| `1list0PlusDot-*_pass`, `1list0PlusIri-*_pass`, `1list1PlusIri-*_pass` (6) | the schema's `[rdf:nil] OR CLOSED { rdf:first . ; rdf:rest @<L> }` ladder shapes over data written as `( ... )`; shexTest-array (cf1d5e7) rewrites them as `LIST * .`, `LIST * IRI`, `LIST + IRI`, and those pass; `src/test/files/shexTest` is still the older copy |
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
| rdf-array contrib (2026-10-25): `Scripts_RdfArrayContrib` 31, `Scripts_RdfArrayContribSPARQL` 63 (in `ARQTestSuite`); `TestShexContribSyntax` 13, `TestShexContribSyntaxBad` 6, `TestShexContribValidation` 38 (in `TS_Shex`) | all pass |
| jena-core (TestPackage, 245), jena-base, jena-iri | pass |
| `TS_Lang` 2200, `TS_Query` 975, `TS_Engine` 40, `TestPathPF`, `TestStrSplit` | pass |
| `Scripts_DAWG` 496, `Scripts_SPARQL11` 466, `Scripts_RDFStar` 102 | pass |
| `Scripts_ARQ` 1111 | 4 fail: `list-length-2`, `filter-1`, `localname-1`, `namespace-1` (expectation files, see table) |
| `Scripts_TIM` 605 / `Scripts_RefEngine` 487 | the same tests through the TIM / reference engines |
| `Scripts_LangSuite` 868 / `Scripts_LangSuiteCG` 799 | 20 / 23 fail: the W3C collection evaluation tests (ladders in `.nt`/`.nq` expectations) |
| `sparql/util/TestList` 46 | 1 fail: `testListTriples_2` (structural) |
| `TS_RiotWriter` 859 (2026-10-25) | 53 errors: the serialisations that cannot hold a sequence; no failures |
| `TestPackage_core` (jena-core) 12411 (2026-10-25) | pass |
| `TC_SHACL` 272 (2026-10-26) | pass; 5 skipped: the tests data-shapes-array marks `sht:rejected` (`path-strange-002`, SHACL 1.2's four node-level list tests) |
| `TestJsonLDReadWrite` | 4 fail: JSON-LD `@list` |
| `TS_Shex` 2165 | 7 fail: six `1list*` ladder-shape validations, `TestParserShExR.roundTripAgainstShExC` (`( () )` fixtures) |

## Test status (2026-11-01, the array syntax of docs/array-syntax.md)

| suite | result |
|-------|--------|
| new: `TestSequenceQueries` 36, `TestArraySyntaxExamples` 14 (§4.1), `TestShexList` 25 (§4.2, §5), `TestLangSequences` 14 | all pass |
| contrib copies refreshed: `Scripts_RdfArrayContrib` 31, `Scripts_RdfArrayContribSPARQL` 96 (rdf-tests-contrib eee4e34); `TestShexContribSyntax` 22, `TestShexContribSyntaxBad` 6, `TestShexContribValidation` 56 (shexTest-contrib e1e7568) | all pass |
| shexTest-array cf1d5e7, run from a scratch copy through the fork's runners (`src/test/files/shexTest` is still the older copy): schemas 491, negativeSyntax 105, validation 1242 | the `1list*` LIST tests pass; the 9 schema errors (`AND3G`, `Extend*`, `2val1refvsAnnot`, `extends-abstract-*`), 4 negative-syntax failures (duplicate-label tests) and 11 validation failures (`*Include1dotclosecardPlus*`, `1dotCode*`, `2val1refvsCode`) are differences between shexTest master and the fork's older copy, not LIST |
| `TS_Lang` 2202, `TS_Query` 975, `TS_Engine` 67, `TS_Algebra` 260, `TS_Optimization` 457, `TS_Syntax` 105, `TS_SSE` 156, `TS_Expr` 1464, `Scripts_DAWG` 496, `Scripts_SPARQL11` 466, `Scripts_RDFStar` 102 | pass |
| `Scripts_ARQ` 1111 | 4 fail: `list-length-2`, `filter-1`, `localname-1`, `namespace-1` (expectation files, as before) |
| `Scripts_RefEngine` 487 | 2 fail: `localname-1`, `namespace-1` (as before) |
| `TS_Shex` 2261 | 7 fail: six `1list*` ladder-shape validations and `TestParserShExR.roundTripAgainstShExC` in the older `shexTest` copy (as before) |
| `TS_RdfArray` 23 | pass |

## SHACL-array (`jena-shacl`, 2026-10-25)

[shacl-array.md](https://rdf-array.github.io/rdf-array/docs/shacl-array.html) in rdf-array/rdf-array
defines it; this is its first implementation.

- **A SHACL list is a sequence term, and only that** (`ShLib.isList`/`listMembers`): `sh:path`
  sequence and alternative paths (`ShaclPaths`), `sh:in`, `sh:languageIn`, `sh:and`/`sh:or`/`sh:xone`
  (`Constraints.list`), `sh:ignoredProperties` (`ClosedConstraint`). A ladder where a list is
  expected is a `ShaclParseException`; `rdf:nil` is an IRI (a predicate path), `sh:path ()` an
  error. `ShaclPaths.pathToRDF` and the SHACL compact syntax parser build sequences, not ladders.
- **SHACL 1.2 list constraints** over sequence values: `sh:memberShape` (`ListMemberShape`),
  `sh:minListLength`/`sh:maxListLength` (`ListMinLength`/`ListMaxLength`), `sh:uniqueMembers`
  (`ListUniqueMembers`), with their vocabulary in `SHACL`. A value that is not a sequence violates
  each. One result per value node; no `sh:detail`. Not in SHACL compact syntax.
- **Tests:** `tests/TestShaclSequences` (ladders refused, `()` and `rdf:nil` paths, compact syntax
  builds sequences) and the manifest tests in `src/test/files/local/rdf-array/` (sequence paths,
  `sh:in`/`sh:or`/`sh:not`, the four list constraints, a ladder and `rdf:nil` as values), run by
  `TS_JenaShacl`.
- **Suites (2026-10-26):** `src/test/files/std` follows
  [data-shapes-array](https://github.com/rdf-array/data-shapes-array) (the rdf-array fork of
  w3c/data-shapes: path-strange-001 and SHACL 1.2's property-level list tests re-recorded, shacl-shacl
  rewritten with `sh:memberShape`), refreshed by `std/update.sh`, which also copies SHACL 1.2's
  list-constraint tests into `std12-lists` (`TestShacl12Lists`);
  [data-shapes-contrib](https://github.com/rdf-array/data-shapes-contrib) is copied into
  `data-shapes-contrib` (`TestShaclContrib`, 21 tests). `ShaclTest` skips a test marked
  `mf:status sht:rejected`, with its `rdfs:comment` as the reason, and an `sht:Failure` test now
  expects validation to throw (it fell through to reading the result as a report before; jena's copy
  excludes the W3C suite's own `sht:Failure` tests in `sparql/pre-binding/manifest.ttl`).
- Found on the way, not fixed (upstream): `ShaclPaths.pathToRDF` writes an inverse path as
  `sh:alternativePath`, not `sh:inversePath`.

## The shared contrib suites

Copies of the rdf-array organisation's shared tests, each refreshed by the `update.sh` beside it,
with the commit in a `*.commit` file:

- `jena-arq/testing/rdf-array/rdf-tests-contrib/` ([rdf-tests-contrib](https://github.com/rdf-array/rdf-tests-contrib)
  `rdf/`, `sparql/`): run by `org.apache.jena.external.Scripts_RdfArrayContrib` (Turtle, TriG,
  N-Triples, N-Quads; `RunnerRIOT`) and `Scripts_RdfArrayContribSPARQL` (eval, update, syntax,
  results; `RunnerSPARQL`). `SparqlTests.makeSPARQLTest` parses these with ARQ syntax: gap
  patterns, `UNNEST` and the sequence functions are ARQ extensions here, not SPARQL 1.1. The
  manifests' `ra:` requirements (`ra:GapPatterns`, `ra:Unnest`, `ra:SequenceFunctions`,
  `ra:ListShapeExpression`) are not read by the runners.
- `jena-shex/src/test/files/shexTest-contrib/` ([shexTest-contrib](https://github.com/rdf-array/shexTest-contrib)):
  run by `TestShexContribSyntax`, `TestShexContribSyntaxBad` and `TestShexContribValidation`. The
  copy comments out the manifests' `@base`, as `../shexTest` does, so the entries resolve to the
  files beside them.
- json-ld-api-contrib does not apply: JSON-LD is not mapped to sequences here.

Running them found and fixed: the JSON results readers (`RowSetReaderJSON_V1`,
`RowSetJSONStreaming`) did not read `{"type":"seq"}`; the XML results neither wrote nor read
`<seq>` (`RowSetWriterXML`, `RowSetReaderXML`, `XMLResults.dfSeq`); the TSV reader
(`NodeFactoryExtra.parseNode`) did not read `( … )`; and `ResultSetCompare.BNodeIso` never found
two sequence terms equal, so every expected result holding one failed.

## Running it

    JAVA_HOME=/opt/homebrew/opt/openjdk@21
    MVN="/Applications/IntelliJ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn"
    "$MVN" -o -q -pl jena-shex -am -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Drat.skip=true -Denforcer.skip=true package
    "$MVN" -o -pl jena-arq,jena-shex -am -Dmaven.javadoc.skip=true -Dmaven.source.skip=true -Drat.skip=true -Denforcer.skip=true test \
        -Dtest='TestLangSequences,TestSequenceQueries,TestArraySyntaxExamples,TestShexList' -Dsurefire.failIfNoSpecifiedTests=false

Never `install` from this worktree: it would overwrite the 5.0.0-SNAPSHOT jars in
`~/.m2` that the main checkout resolves offline. Grammar regeneration:
`jena-arq/Grammar/grammar` and `jena-shex/shexc/shex-parser` with `javacc`,
`jjdoc` and a comment-stripping `cpp` on the PATH (see the memory note).

## rdf-undef

Branch `rdf-undef`: the empty slot of a sequence, `( 1 undef 3 )`, as rdf-array's docs/rdf-undef.md says.
`Node.UNDEF` (`Node_Undef`) is one object, a member of a `Node_Seq` and nothing else. The behaviours are
`TestUndefSlots` (43 tests, in `TS_Engine`). The contrib copy in `jena-arq/testing/rdf-array` is at
rdf-tests-contrib's `rdf-undef` branch: `Scripts_RdfArrayContrib` 58 and `Scripts_RdfArrayContribSPARQL` 207,
no failures, including its `undef-*` tests. Those found that `ResultSetCompare.BNodeIso` had no case for a slot.

`VALUES` takes no sequence, with or without a slot, so slots reach data through `INSERT DATA` and `DELETE DATA`.
CSV results write `?` for any sequence (Comunica and Oxigraph write it out). Both predate slots.

ShEx and SHACL are not decided (docs/rdf-undef.md §5): no code in jena-shex or jena-shacl names a slot. Today
`LIST * xsd:integer` fails over `( 1 undef )`, and `LIST * .` holds, where rudof-array has it fail.
