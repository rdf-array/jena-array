# rdf-array (jena-rdfarray)

A small HTTP server and one page that show the RDF Sequences data model of the
`rdf-sequences` branch: Turtle-array, SPARQL-array and ShEx-array share one data panel.

    mvn -o -q -pl jena-rdfarray -am -DskipTests -Dmaven.javadoc.skip=true -Dmaven.source.skip=true package
    jena-rdfarray/bin/rdfarray -S http://localhost:8099/validate
    open http://localhost:8099/

Routes (GET or POST; query string, URL-encoded form, multipart upload or JSON body):

| route | parameters | returns |
|-------|------------|---------|
| `/` | | the page |
| `/turtle` | `data` (Turtle/TriG), `base` | JSON: `count`, `quads` (terms as SPARQL-results JSON, plus `"type": "seq"`), `ntriples`, `turtle` |
| `/sparql` | `query`, `data` (or preloaded `-d`) | SELECT: `application/sparql-results+json` (sequence values have `"type": "seq"`); ASK: JSON boolean; CONSTRUCT/DESCRIBE: Turtle |
| the path of `-S` (default `/validate`) | `schema`, `data`, `queryMap` or `node` + `shape`, `report=boolean` | JSON: `conforms`, `results` (node, shape, status), `report`; or a bare boolean |

Errors are `{"type": "ParsingError", "errors": ["..."]}` with status 400, like the
shex.js validation server, whose options this server copies: `-x schema -d data -s shape -n node -m shapeMap`
preload what requests may then omit, `--serve-n N` stops after N requests, `-Q` is quiet, `--terse`
prints only host and port.

Tests: `mvn -o -pl jena-rdfarray -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=TS_RdfArray`.
