/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package shex;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.atlas.logging.LogCtl;
import org.apache.jena.cmd.ArgDecl;
import org.apache.jena.cmd.CmdException;
import org.apache.jena.cmd.CmdGeneral;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexException;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shexmap.Analysis;
import org.apache.jena.shexmap.Bindings;
import org.apache.jena.shexmap.Materializer;
import org.apache.jena.shexmap.ShExMap;
import org.apache.jena.shexmap.ShExMapException;
import org.apache.jena.shexmap.Terms;
import org.apache.jena.sparql.graph.GraphFactory;
import org.apache.jena.sys.JenaSystem;

/** {@code shex map}: map RDF from one ShEx schema to another with ShExMap ({@code %Map:{ %}})
 * actions.  Bind an input node against the input schema, then materialize the output schema
 * from the bindings; either half runs alone ({@code --bindings-out}, {@code --bindings}), and
 * {@code --check} analyses a schema pair without data. */
public class shex_map extends CmdGeneral {
    static {
        LogCtl.setLogging();
        JenaSystem.init();
    }

    private final ArgDecl argData         = new ArgDecl(true, "--data", "--datafile", "-d");
    private final ArgDecl argSchema       = new ArgDecl(true, "--schema", "--shapes", "-s");
    private final ArgDecl argNode         = new ArgDecl(true, "--node", "--target", "--focus", "-n");
    private final ArgDecl argShape        = new ArgDecl(true, "--shape", "--start", "-l");
    private final ArgDecl argBindings     = new ArgDecl(true, "--bindings", "-j");
    private final ArgDecl argBindingsOut  = new ArgDecl(true, "--bindings-out", "-b");
    private final ArgDecl argPrintBindings = new ArgDecl(false, "--print-bindings");
    private final ArgDecl argOutputSchema = new ArgDecl(true, "--output-schema", "--outschema", "-O");
    private final ArgDecl argRoot         = new ArgDecl(true, "--root", "-r");
    private final ArgDecl argOutputShape  = new ArgDecl(true, "--output-shape", "--outshape");
    private final ArgDecl argStatic       = new ArgDecl(true, "--static");
    private final ArgDecl argStrict       = new ArgDecl(false, "--strict");
    private final ArgDecl argInto         = new ArgDecl(true, "--into");
    private final ArgDecl argProvenance   = new ArgDecl(true, "--provenance");
    private final ArgDecl argCheck        = new ArgDecl(false, "--check");
    private final ArgDecl argOutput       = new ArgDecl(true, "--output", "-o");
    private final ArgDecl argFormat       = new ArgDecl(true, "--format", "--out-format", "-f");
    private final ArgDecl argBase         = new ArgDecl(true, "--base", "--base-schema");
    private final ArgDecl argBaseData     = new ArgDecl(true, "--base-data");

    private String data, schema, node, shape, bindingsFile, bindingsOut, outputSchema, root, outputShape;
    private String staticsFile, into, provenance, output, format, base, baseData;
    private boolean strict, check, printBindings;

    public static void main(String... argv) {
        new shex_map(argv).mainRun();
    }

    public shex_map(String[] argv) {
        super(argv);
        super.add(argData,         "--data",          "Input RDF data file");
        super.add(argSchema,       "--schema",        "Input ShEx schema (its %Map:{ %} actions bind)");
        super.add(argNode,         "--node",          "Input node to map [may use prefixes from the data]");
        super.add(argShape,        "--shape",         "Input shape label (default: the input schema's start)");
        super.add(argBindings,     "--bindings",      "Read bindings JSON (as written by --bindings-out, shex.js or PyShEx) instead of binding data");
        super.add(argBindingsOut,  "--bindings-out",  "Write the bindings as JSON to this file");
        super.add(argPrintBindings, "--print-bindings", "Print the bindings as JSON");
        super.add(argOutputSchema, "--output-schema", "Output ShEx schema (its %Map:{ %} actions place the bindings)");
        super.add(argRoot,         "--root",          "Output node to build (default: a blank node)");
        super.add(argOutputShape,  "--output-shape",  "Output shape label (default: the output schema's start)");
        super.add(argStatic,       "--static",        "JSON object of extra variable values, as shex.js's staticVars");
        super.add(argStrict,       "--strict",        "Fail when the input or the output can be matched in more than one way");
        super.add(argInto,         "--into",          "An existing RDF file to update in place: what the output schema holds at --root is replaced");
        super.add(argProvenance,   "--provenance",    "Write one JSON object per output triple here: its origin in the input");
        super.add(argCheck,        "--check",         "Only analyse --schema against --output-schema, without data; exit 1 on errors");
        super.add(argOutput,       "--output",        "Write the output RDF here (default: stdout)");
        super.add(argFormat,       "--format",        "Output RDF syntax (default: Turtle)");
        super.add(argBase,         "--base",          "Base IRI for relative IRIs in the schemas, shape labels and nodes");
        super.add(argBaseData,     "--base-data",     "Base IRI for relative IRIs in the RDF data (default: --base)");
    }

    @Override
    protected String getSummary() {
        return getCommandName() + " --data FILE --schema FILE --node NODE --output-schema FILE [--root NODE] | --check --schema FILE --output-schema FILE";
    }

    @Override
    protected void processModulesAndArgs() {
        super.processModulesAndArgs();
        data = getValue(argData);
        schema = getValue(argSchema);
        node = getValue(argNode);
        shape = getValue(argShape);
        bindingsFile = getValue(argBindings);
        bindingsOut = getValue(argBindingsOut);
        outputSchema = getValue(argOutputSchema);
        root = getValue(argRoot);
        outputShape = getValue(argOutputShape);
        staticsFile = getValue(argStatic);
        into = getValue(argInto);
        provenance = getValue(argProvenance);
        output = getValue(argOutput);
        format = getValue(argFormat);
        base = getValue(argBase);
        baseData = getValue(argBaseData) != null ? getValue(argBaseData) : base;
        strict = hasArg(argStrict);
        check = hasArg(argCheck);
        printBindings = hasArg(argPrintBindings);
        if (data == null && positionals.size() == 1)
            data = positionals.get(0);
        if (check) {
            if (schema == null || outputSchema == null)
                throw new CmdException("--check needs --schema and --output-schema");
            return;
        }
        if (bindingsFile != null) {
            if (data != null || schema != null || node != null)
                throw new CmdException("--bindings replaces --data, --schema and --node");
        } else if (data == null || schema == null || node == null) {
            throw new CmdException("give --data, --schema and --node, or --bindings\nUsage: " + getSummary());
        }
        if (outputSchema == null && bindingsOut == null && !printBindings)
            throw new CmdException("nothing to do: give --output-schema, --bindings-out or --print-bindings");
    }

    @Override
    protected void exec() {
        try {
            if (check) {
                Analysis.Report report = ShExMap.analyse(readSchema(schema), readSchema(outputSchema),
                        label(shape), label(outputShape), statics().keySet());
                System.out.println(report);
                if (!report.ok())
                    throw new CmdException("the schemas do not map coherently");
                return;
            }
            Bindings bindings;
            if (bindingsFile != null) {
                bindings = Bindings.loads(Files.readString(Path.of(bindingsFile), StandardCharsets.UTF_8));
            } else {
                Graph graph = load(data);
                Node focus = parseNode(node, graph, baseData);
                bindings = ShExMap.bind(graph, readSchema(schema), focus, label(shape), strict);
                if (bindings.isAmbiguous())
                    System.err.println("shex map: warning: the input matches in " + bindings.alternatives()
                            + " ways that bind differently; using the first (--strict makes this an error)");
            }
            if (printBindings)
                System.out.println(bindings.dumps(true));
            if (bindingsOut != null)
                Files.writeString(Path.of(bindingsOut), bindings.dumps(true) + "\n", StandardCharsets.UTF_8);
            if (outputSchema == null)
                return;
            Materializer.Options options = new Materializer.Options();
            statics().forEach((var, term) -> options.staticVar(var, Terms.fromJson(term)));
            Materializer m = new Materializer(readSchema(outputSchema), options);
            Node rootNode = root == null ? NodeFactory.createBlankNode() : parseNode(root, null, base);
            Graph out;
            List<Triple> triples;
            if (into != null) {
                out = load(into);
                Materializer.Update u = m.update(out, bindings.tree(), rootNode, label(outputShape));
                triples = m.chosen().triples();
                System.err.println("shex map: " + into + ": " + u.added().size() + " triples added, " + u.removed().size() + " removed");
            } else {
                out = GraphFactory.createDefaultGraph();
                triples = m.materialize(bindings.tree(), rootNode, label(outputShape));
                triples.forEach(out::add);
            }
            if (m.accepts().size() > 1) {
                if (strict)
                    throw new CmdException("the bindings fit the output schema in " + m.accepts().size() + " ways");
                System.err.println("shex map: warning: the bindings fit the output schema in " + m.accepts().size()
                        + " ways; using the one that uses the most bindings");
            }
            m.prefixes().forEach((p, ns) -> { if (out.getPrefixMapping().getNsPrefixURI(p) == null) out.getPrefixMapping().setNsPrefix(p, ns); });
            if (provenance != null)
                try (PrintStream ps = new PrintStream(new FileOutputStream(provenance), false, StandardCharsets.UTF_8)) {
                    List<Materializer.Source> sources = m.provenance();
                    for (int i = 0; i < triples.size(); i++)
                        ps.println(JSON.toStringFlat(provenanceJson(triples.get(i), sources.get(i))));
                }
            Lang lang = format == null ? Lang.TURTLE : RDFLanguages.nameToLang(format);
            if (lang == null)
                throw new CmdException("unknown RDF syntax: " + format);
            String dest = output != null ? output : into;
            if (dest != null) {
                try (OutputStream os = new FileOutputStream(dest)) {
                    RDFDataMgr.write(os, out, lang);
                }
            } else {
                RDFDataMgr.write(System.out, out, lang);
            }
        } catch (ShExMapException | ShexException | RiotException ex) {
            throw new CmdException("shex map: " + ex.getMessage());
        } catch (IOException ex) {
            throw new CmdException("shex map: " + ex.getMessage());
        }
    }

    private ShexSchema readSchema(String file) {
        return base == null ? Shex.readSchema(file) : Shex.readSchema(file, base);
    }

    private Graph load(String file) {
        if (baseData == null)
            return RDFDataMgr.loadGraph(file);
        return org.apache.jena.riot.RDFParser.create().source(file).base(baseData)
                .lang(RDFLanguages.filenameToLang(file, Lang.TURTLE)).toGraph();
    }

    private Node label(String text) {
        if (text == null || text.equals("START"))
            return null;
        return parseNode(text, null, base);
    }

    /** A node: {@code _:label}, {@code <iri>}, a prefixed name known to the data, or a bare IRI,
     * relative ones resolved against {@code base}. */
    private static Node parseNode(String text, Graph graph, String base) {
        text = text.strip();
        if (text.startsWith("_:"))
            return NodeFactory.createBlankNode(text.substring(2));
        if (text.startsWith("\""))
            return Terms.parseTerm(text);
        String iri = text.startsWith("<") && text.endsWith(">") ? text.substring(1, text.length() - 1) : text;
        if (graph != null && !text.startsWith("<")) {
            String expanded = graph.getPrefixMapping().expandPrefix(iri);
            if (!expanded.equals(iri))
                return NodeFactory.createURI(expanded);
        }
        if (base != null)
            iri = org.apache.jena.irix.IRIx.create(base).resolve(iri).str();
        return NodeFactory.createURI(iri);
    }

    private Map<String, JsonValue> statics() throws IOException {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        if (staticsFile == null)
            return out;
        JsonValue v = JSON.parseAny(Files.readString(Path.of(staticsFile), StandardCharsets.UTF_8));
        if (!v.isObject())
            throw new CmdException(staticsFile + " must hold a JSON object of variable IRIs to values");
        for (String k : v.getAsObject().keys())
            out.put(k, v.getAsObject().get(k));
        return out;
    }

    /** A triple's provenance record as JSON: terms in N3, the input node likewise. */
    private static JsonObject provenanceJson(Triple t, Materializer.Source s) {
        JsonObject o = new JsonObject();
        o.put("subject", Terms.n3(t.getSubject()));
        o.put("predicate", Terms.n3(t.getPredicate()));
        o.put("object", Terms.n3(t.getObject()));
        o.put("constraint", s.predicate());
        org.apache.jena.atlas.json.JsonArray scope = new org.apache.jena.atlas.json.JsonArray();
        s.scope().forEach(i -> scope.add(org.apache.jena.atlas.json.JsonNumber.value((long) i)));
        o.put("scope", scope);
        if (s.node() != null)
            o.put("node", Terms.n3(s.node()));
        org.apache.jena.atlas.json.JsonArray reads = new org.apache.jena.atlas.json.JsonArray();
        for (Materializer.Read r : s.reads()) {
            org.apache.jena.atlas.json.JsonArray pair = new org.apache.jena.atlas.json.JsonArray();
            org.apache.jena.atlas.json.JsonArray path = new org.apache.jena.atlas.json.JsonArray();
            r.path().forEach(i -> path.add(org.apache.jena.atlas.json.JsonNumber.value((long) i)));
            pair.add(path);
            pair.add(r.variable());
            reads.add(pair);
        }
        o.put("reads", reads);
        org.apache.jena.atlas.json.JsonArray statics = new org.apache.jena.atlas.json.JsonArray();
        s.statics().forEach(statics::add);
        o.put("statics", statics);
        String kind = s.kind().name().toLowerCase();
        o.put("kind", kind);
        if (s.detail() != null)
            o.put(kind, s.detail());
        else
            o.put(kind, true);
        return o;
    }

    @Override
    protected String getCommandName() {
        return "shex_map";
    }
}
