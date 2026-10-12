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

package org.apache.jena.shex.manifest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.irix.IRIs;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.shex.ShapeMap;
import org.apache.jena.shex.ShapeMapElement;
import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexException;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.ShexValidator;
import org.apache.jena.shex.jsonld.ShexJsonldDeserializer;
import org.apache.jena.shex.parser.ShExR;
import org.apache.jena.shex.reporting.ShexValidationReport;
import org.apache.jena.shex.reporting.SimpleExhaustiveReporter;
import org.apache.jena.shex.sys.SysShex;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * Running a manifest's entries: each validated as a command line would validate it -- the
 * schema (ShExC, ShExJ or ShExR; a schema written as RDF may be one node, named by a
 * {@code #fragment}, of the document that is also the manifest and the data), the data (one
 * document or several, merged), and a shape map from {@code queryMap} / {@code queryMapURL} or
 * from {@code node} and {@code shape} -- and its verdict set against the {@code status} it
 * expects.
 */
public class ManifestRunner {

    /** What validating an entry gave. */
    public enum Outcome {
        CONFORMANT, NONCONFORMANT, ERROR;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One entry, run: its outcome, and the validation report when it got that far. */
    public static final class EntryRun {
        public final ManifestEntry entry;
        public final Outcome outcome;
        /** What stopped the entry, when something did. */
        public final String error;
        public final ShexValidationReport report;

        EntryRun(ManifestEntry entry, Outcome outcome, String error, ShexValidationReport report) {
            this.entry = entry;
            this.outcome = outcome;
            this.error = error;
            this.report = report;
        }

        /** {@code conformant}, {@code nonconformant} or {@code error}. */
        public String verdict() {
            return outcome.toString();
        }

        /** Whether the verdict is the one the entry's {@code status} expects; without a status, whether the data conformed. */
        public boolean passed() {
            String expected = entry.status();
            if ( expected == null )
                return outcome == Outcome.CONFORMANT;
            return expected.trim().equalsIgnoreCase(verdict());
        }
    }

    /** Runs the entries of the manifest at a file name or URL; see {@link #run(List, String, List)}. */
    public static List<EntryRun> run(String manifestFileOrURL, List<Pattern> patterns) {
        String base = IRILib.filenameToIRI(manifestFileOrURL);
        return run(ManifestReader.read(manifestFileOrURL), base, patterns);
    }

    /**
     * Runs the entries whose label one of {@code patterns} matches (all of them when there are
     * none), an {@link EntryRun} apiece. An entry that cannot be read or run is its own error
     * outcome; a selection that matches nothing is an exception.
     *
     * @param base what the entries' references resolve against: the manifest's URL
     */
    public static List<EntryRun> run(List<ManifestEntry> entries, String base, List<Pattern> patterns) {
        List<EntryRun> runs = new ArrayList<>();
        for ( ManifestEntry entry : entries ) {
            if ( selected(entry, patterns) )
                runs.add(runEntry(entry, base));
        }
        if ( runs.isEmpty() && patterns != null && !patterns.isEmpty() )
            throw new ShexException("No manifest entry matches " + patterns);
        return runs;
    }

    private static boolean selected(ManifestEntry entry, List<Pattern> patterns) {
        if ( patterns == null || patterns.isEmpty() )
            return true;
        String label = entry.label();
        return patterns.stream().anyMatch(p -> p.matcher(label).find());
    }

    /** One entry validated; whatever stops it is its outcome rather than the manifest's error. */
    public static EntryRun runEntry(ManifestEntry entry, String base) {
        try {
            ShexValidationReport report = validate(entry, base);
            return new EntryRun(entry, report.conforms() ? Outcome.CONFORMANT : Outcome.NONCONFORMANT, null, report);
        } catch (RuntimeException ex) {
            return new EntryRun(entry, Outcome.ERROR, ex.getMessage() == null ? ex.toString() : ex.getMessage(), null);
        }
    }

    /** Validates an entry: the data, the schema, the shape map, as the entry names them. */
    public static ShexValidationReport validate(ManifestEntry entry, String base) {
        String label = entry.label();

        // the data: the entry's own texts are Turtle at the manifest's URL; the documents it
        // names are read from theirs; a dataBase overrides both
        String dataBaseOverride = entry.dataBase() == null ? null : IRIs.resolve(base, entry.dataBase());
        List<String> dataURLs = new ArrayList<>();
        for ( String url : entry.dataURLs() )
            dataURLs.add(IRIs.resolve(base, url));
        if ( entry.dataTexts().isEmpty() && dataURLs.isEmpty() )
            throw new ShexException(label + ": no data or dataURL");
        String dataBase = dataBaseOverride != null ? dataBaseOverride : !dataURLs.isEmpty() ? dataURLs.get(0) : base;
        Graph data = GraphFactory.createDefaultGraph();
        for ( String text : entry.dataTexts() )
            RDFParser.fromString(text, Lang.TURTLE).base(dataBaseOverride != null ? dataBaseOverride : base).parse(data);
        for ( String url : dataURLs )
            RDFParser.source(url).base(dataBaseOverride != null ? dataBaseOverride : url).parse(data);

        // the schema: the entry's text, or the document its reference names, read in the format
        // its extension says -- a reference without one in the first of its representations that is there
        ShexSchema schema;
        String schemaDocument;
        if ( entry.schemaText() != null ) {
            String text = entry.schemaText();
            schema = text.stripLeading().startsWith("{")
                ? new ShexJsonldDeserializer().deserializeSchema(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))
                : Shex.schemaFromString(text, base);
            schemaDocument = base;
        } else if ( entry.schemaURL() != null ) {
            String iri = IRIs.resolve(base, entry.schemaURL());
            int hash = iri.indexOf('#');
            String document = hash < 0 ? iri : iri.substring(0, hash);
            String fragment = hash < 0 ? null : iri.substring(hash + 1);
            document = negotiate(document);
            switch ( extensionOf(document) ) {
                case "json", "shexj" : {
                    try ( InputStream in = RDFDataMgr.open(document) ) {
                        schema = new ShexJsonldDeserializer().deserializeSchema(in);
                    } catch (IOException ex) {
                        throw new ShexException(label + ": reading " + document + ": " + ex.getMessage(), ex);
                    }
                    break;
                }
                case "ttl", "nt", "rdf", "xml", "nq", "trig", "n3", "jsonld" : {
                    // ShExR: the schema node is the one the fragment names, else the document's one
                    Graph graph = RDFParser.source(document).base(document).toGraph();
                    schema = fragment == null
                        ? ShExR.parse(graph, document, document)
                        : ShExR.parse(graph, NodeFactory.createURI(iri), document, document);
                    break;
                }
                default :
                    schema = Shex.readSchema(document, document);
            }
            schemaDocument = document;
        } else
            throw new ShexException(label + ": no schema or schemaURL");

        // the shape map: its shapes resolve against the BASE the schema declares, else the
        // schema's document; its nodes against the data's base
        String schemaBase = schema.getBase() != null ? schema.getBase() : schemaDocument;
        ShapeMap shapeMap;
        if ( entry.queryMap() != null )
            shapeMap = parseQueryMap(entry.queryMap(), dataBase, schemaBase);
        else if ( entry.queryMapURL() != null )
            shapeMap = parseQueryMap(ManifestReader.readText(IRIs.resolve(base, entry.queryMapURL())), dataBase, schemaBase);
        else if ( !entry.nodes().isEmpty() ) {
            ShapeMap.Builder builder = ShapeMap.newBuilder();
            Node shape = entry.shape() == null ? SysShex.startNode : term(entry.shape(), schemaBase);
            for ( String node : entry.nodes() )
                builder.add(term(node, dataBase), shape);
            shapeMap = builder.build();
        } else
            throw new ShexException(label + ": no queryMap, queryMapURL, or node");
        if ( shapeMap.entries().isEmpty() )
            throw new ShexException(label + ": nothing to validate: the shape map is empty");

        return validator().validate(data, schema, shapeMap);
    }

    /** A validator that reports why a node fails, as the report of a failing entry needs. */
    private static ShexValidator validator() {
        ShexValidator validator = ShexValidator.getNew(List.of());
        validator.setReporter(SimpleExhaustiveReporter.factory());
        return validator;
    }

    /** A manifest's node or shape: a blank node label, START, or an IRI resolved against {@code base}. */
    private static Node term(String text, String base) {
        if ( text.startsWith("_:") )
            return NodeFactory.createBlankNode(text.substring(2));
        if ( text.equals("START") )
            return SysShex.startNode;
        return NodeFactory.createURI(IRIs.resolve(base, text));
    }

    // The shape map parser resolves every relative IRI against one base; a manifest's query
    // map resolves its nodes against the data and its shapes against the schema. Parse against
    // a base nothing else uses, then re-resolve what landed under it.
    private static final String DUMMY_ORIGIN = "http://shex-manifest.invalid";
    private static final String DUMMY_BASE = DUMMY_ORIGIN + "/base/";

    /** The pairs of a query map in the compact syntax, nodes against {@code dataBase} and shapes against {@code schemaBase}. */
    public static ShapeMap parseQueryMap(String text, String dataBase, String schemaBase) {
        ShapeMap parsed = Shex.shapeMapFromString(text, DUMMY_BASE);
        ShapeMap.Builder builder = ShapeMap.newBuilder();
        for ( ShapeMapElement e : parsed.entries() ) {
            Node shape = rebase(e.shapeExprLabel, schemaBase);
            if ( e.patternSelector != null ) {
                Triple p = e.patternSelector;
                builder.add(Triple.create(rebase(p.getSubject(), dataBase), rebase(p.getPredicate(), dataBase),
                                          rebase(p.getObject(), dataBase)), shape);
            } else
                builder.add(rebase(e.nodeSelector, dataBase), shape);
        }
        return builder.build();
    }

    private static Node rebase(Node node, String base) {
        if ( node == null || !node.isURI() )
            return node;
        String uri = node.getURI();
        if ( uri.startsWith(DUMMY_BASE) )
            return NodeFactory.createURI(IRIs.resolve(base, uri.substring(DUMMY_BASE.length())));
        if ( uri.startsWith(DUMMY_ORIGIN) )
            return NodeFactory.createURI(IRIs.resolve(base, uri.substring(DUMMY_ORIGIN.length())));
        return node;
    }

    private static String extensionOf(String iri) {
        String path = iri.split("[?#]", 2)[0];
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        return dot > slash ? path.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    /** A reference without an extension is read in the first of its representations that is there. */
    private static String negotiate(String document) {
        List<String> candidates = ManifestReader.representations(document);
        if ( candidates.size() == 1 )
            return document;
        for ( String candidate : candidates ) {
            if ( exists(candidate) )
                return candidate;
        }
        return document;
    }

    private static boolean exists(String iri) {
        try {
            if ( iri.startsWith("file:") )
                return Files.exists(Path.of(URI.create(iri)));
            try ( InputStream in = RDFDataMgr.open(iri) ) {
                return in != null;
            }
        } catch (RuntimeException | IOException ex) {
            return false;
        }
    }
}
