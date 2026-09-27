# Apache Jena - ShExMap

`jena-shexmap` maps RDF from one ShEx schema to another with
[ShExMap](http://shex.io/extensions/Map/) semantic actions.  A `%Map:{ ... %}` action on a
triple constraint names a variable: in the **input schema** it binds the variable to the value
the constraint matched; in the **output schema** it says where the bound value goes.  Two
functions transform values on the way (`regex(/(?<v:family>[a-zA-Z]+), (?<v:given>[a-zA-Z]+)/)`
splits and joins, `hashmap(v:status, {"D": "Divorced", "M": "Married"})` recodes), and
`id(v:mrn)` on a shape-valued constraint names the node the output builds, so that two
constraints with the same key share it.

The design is the one shared by shex.js's `@shexjs/extension-map` and PyShEx's
`pyshex.shexmap`: what each constraint matched is kept as a **binding tree** whose nesting
follows the input schema's repeated constraints, and an output repetition iterates the input
list its variables are bound at, so a reading's systolic and diastolic values stay together and
a patient's name is read in every reading.  The bindings JSON is the same in all three.

## Library

```java
ShexSchema in  = Shex.readSchema("BPfhir-schema.shex");
ShexSchema out = Shex.readSchema("BPdam-schema.shex");
Graph data     = RDFDataMgr.loadGraph("BPfhir-instance.ttl");

Bindings bindings = ShExMap.bind(data, in, NodeFactory.createURI("tag:BPfhir123"), null, false);
Graph mapped      = ShExMap.materialize(out, bindings, NodeFactory.createURI("tag:b0"), null, null);
```

* `ShExMap.bind` validates the focus with Jena's ShEx validator (the Map plugin makes
  `regex()` and `hashmap()` codes part of validation), then partitions each neighbourhood by
  exhaustive search.  An input that conforms in several ways that bind differently is
  ambiguous: `Bindings.alternatives()` counts the ways, `ShExMap.bindAll` returns them all,
  `strict` refuses.  `EXTENDS` and inverse constraints are followed.
* `Materializer` builds the output from the tree's structure and keeps the alternatives a
  `OneOf` or an extension leaves open (`accepts()`), returning the one that reads the most
  bindings.  Values are checked against node constraints (plain literals retyped to the
  constraint's datatype); a variable on a shape-valued constraint names its node, `id(...)`
  keys one, other nodes are minted reproducibly.  `provenance()` says where each triple came
  from; `update(graph, ...)` replaces what the schema holds at a root in an existing graph.
* `ShExMap.analyse` checks a schema pair before any data: every variable the output reads is
  bound by the input where it can be read from, every output repetition has one input list to
  iterate, `id()` sits on a shape-valued constraint; unused bindings are warnings.
* `Bindings.loads` / `dumps` read and write the JSON; `MapSemanticActionPlugin` is the
  extension for validators that should check the codes.

## Command line

```sh
shex map --data BPfhir-instance.ttl --schema BPfhir-schema.shex --node '<tag:BPfhir123>' \
         --output-schema BPdam-schema.shex --root '<tag:b0>' --output-shape BPunitsDAM \
         --base http://a.example/schema/
```

`--bindings-out FILE` writes the bindings JSON (`--print-bindings` prints it) and `--bindings`
reads it back instead of binding data; `--base-data` gives the data its own base IRI; `--static FILE` supplies extra variable values; `--strict` refuses ambiguity; `--into
FILE` updates a graph in place; `--provenance FILE` writes one JSON object per output triple;
`--check` analyses the two schemas without data and exits 1 on errors.

## Tests and examples

`src/test/files/shexmap/` holds the example manifests of shex.js's extension-map (`shexjs/`)
and of PyShEx (`pyshex/`: ambiguity, EXTENDS, inverse constraints); `TestShExMapExamples`
binds and materializes each, compares the bindings with what shex.js recorded (blank-node
labels and list order aside, since the parser labels anonymous nodes and lists neighbourhoods
as it pleases), compares the output with the expected graph, and checks that a schema maps to
itself (bind after materialize after bind is bind).

```sh
mvn -pl jena-shex,jena-shexmap test -Dtest=TS_ShExMap -Dsurefire.failIfNoSpecifiedTests=false
```
