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

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.apache.jena.atlas.io.IO;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonBoolean;
import org.apache.jena.atlas.json.JsonNull;
import org.apache.jena.atlas.json.JsonNumber;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.shex.ShexException;
import org.apache.jena.system.G;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.apache.jena.vocabulary.XSD;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reading a ShEx examples manifest -- the format shex.js's {@code @shexjs/manifest} reads --
 * into flat entries.
 * <p>
 * An entry names a schema, data and what to validate, and the verdict expected:
 * {@code schemaLabel}, {@code schemaURL} (a reference) or {@code schema} (the text),
 * {@code dataLabel}, {@code dataURL} (one reference or several) or {@code data},
 * {@code queryMap} or {@code queryMapURL}, or the one-pair form {@code node} and {@code shape},
 * {@code status}, {@code comment}, {@code name}. Three representations carry the same entries:
 * <ul>
 * <li>YAML-LD, or JSON: {@code {"@context": [...], "entries": [...]}}, read plainly (the known
 * contexts make the plain reading its JSON-LD reading), or a deployed classic manifest -- a bare
 * list of flat entries, or one entry, with no {@code @context} -- which is upgraded first;</li>
 * <li>Turtle: the graph those documents expand to, read back by predicate IRI through the ShEx
 * manifest vocabulary ({@code http://www.w3.org/ns/shex-manifest#}), the ShEx vocabulary's
 * {@code node}, {@code shape} and {@code status}, and shex.js's own terms
 * ({@code https://shex.js.org/doc/manifest-context.jsonld#}).</li>
 * </ul>
 * An extension's attributes live in a {@code <prefix>:parms} scope; here they are flattened
 * beside the core attributes, as shex.js's plain reading does, each scope's {@code pluginURL}
 * collected under {@code plugins} and the scopes kept under {@code parms} by namespace.
 */
public class ManifestReader {
    /** The ShEx manifest vocabulary. */
    public static final String SHEXMAN = "http://www.w3.org/ns/shex-manifest#";
    /** The ShEx vocabulary: {@code node}, {@code shape} and {@code status} live there. */
    public static final String SHEX = "http://www.w3.org/ns/shex#";
    /** shex.js's own manifest terms. */
    public static final String SHEXJS = "https://shex.js.org/doc/manifest-context.jsonld#";
    private static final String RDFS_COMMENT = RDFS.comment.getURI();
    private static final String XSD_NS = XSD.getURI();

    private static final List<String> REPRESENTATIONS = List.of(".shex", ".json", ".ttl");
    private static final List<String> DOCUMENTS = List.of("schema", "data", "queryMap");

    /** The three representations a manifest is written in. */
    public enum Format { YAML, JSON, TURTLE }

    /** The entries of the manifest at a file name or URL. */
    public static List<ManifestEntry> read(String filenameOrURL) {
        String url = IRILib.filenameToIRI(filenameOrURL);
        return read(readText(filenameOrURL), url);
    }

    /** The text of a file or URL. */
    public static String readText(String filenameOrURL) {
        try ( InputStream in = RDFDataMgr.open(filenameOrURL) ) {
            return IO.readWholeFileAsUTF8(in);
        } catch (IOException ex) {
            throw new ShexException("Reading " + filenameOrURL + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * The entries of a manifest's text, whichever representation it is in. {@code url} is
     * where the text came from: its format's hint, and what a Turtle manifest's IRIs resolve against.
     */
    public static List<ManifestEntry> read(String text, String url) {
        switch ( detectFormat(text, url) ) {
            case TURTLE : {
                Graph graph = RDFParser.fromString(text, Lang.TURTLE).base(url).toGraph();
                return readGraph(graph);
            }
            case JSON :
                return readJson(JSON.parseAny(text));
            default : {
                Object yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
                return readJson(yamlToJson(yaml));
            }
        }
    }

    /**
     * {@code YAML}, {@code JSON} or {@code TURTLE}. The URL's extension says it when it has
     * one; otherwise the first thing past any leading comment lines (all three allow {@code #}
     * comments) does: {@code [} or <code>{</code> is JSON, a Turtle directive or a term is
     * Turtle, anything else is YAML.
     */
    public static Format detectFormat(String text, String url) {
        if ( url != null ) {
            String path = url.split("[?#]", 2)[0];
            int dot = path.lastIndexOf('.');
            if ( dot >= 0 && dot > path.lastIndexOf('/') ) {
                switch ( path.substring(dot + 1).toLowerCase(Locale.ROOT) ) {
                    case "ttl", "trig", "nt", "nq" : return Format.TURTLE;
                    case "json", "jsonld" :         return Format.JSON;
                    case "yaml", "yml" :            return Format.YAML;
                    default :                       break;
                }
            }
        }
        String lead = text.stripLeading();
        while ( lead.startsWith("#") ) {
            int nl = lead.indexOf('\n');
            lead = nl < 0 ? "" : lead.substring(nl + 1).stripLeading();
        }
        if ( lead.startsWith("[") || lead.startsWith("{") )
            return Format.JSON;
        String firstWord = lead.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if ( lead.startsWith("<") || lead.startsWith("_:")
             || List.of("@prefix", "@base", "prefix", "base").contains(firstWord) )
            return Format.TURTLE;
        return Format.YAML;
    }

    // -- the plain (YAML / JSON) reading -----------------------------------------------------

    /** A YAML document (snakeyaml's objects) as the JSON value YAML-LD means by it. */
    static JsonValue yamlToJson(Object yaml) {
        if ( yaml == null )
            return JsonNull.instance;
        if ( yaml instanceof Map<?, ?> map ) {
            JsonObject obj = new JsonObject();
            map.forEach((k, v) -> obj.put(String.valueOf(k), yamlToJson(v)));
            return obj;
        }
        if ( yaml instanceof List<?> list ) {
            JsonArray array = new JsonArray();
            list.forEach(item -> array.add(yamlToJson(item)));
            return array;
        }
        if ( yaml instanceof Boolean b )
            return new JsonBoolean(b);
        if ( yaml instanceof Integer || yaml instanceof Long )
            return JsonNumber.value(((Number)yaml).longValue());
        if ( yaml instanceof BigDecimal d )
            return JsonNumber.value(d);
        if ( yaml instanceof Number n )
            return JsonNumber.value(n.doubleValue());
        return new JsonString(yaml.toString());
    }

    /** The entries of a parsed YAML or JSON manifest, upgraded, flattened and settled. */
    public static List<ManifestEntry> readJson(JsonValue doc) {
        JsonObject upgraded = upgrade(doc);
        Map<String, String> scopes = boundScopes(upgraded.get("@context"));
        List<ManifestEntry> entries = new ArrayList<>();
        JsonValue entryList = upgraded.get("entries");
        if ( entryList != null && entryList.isArray() ) {
            for ( JsonValue entry : entryList.getAsArray() ) {
                if ( !entry.isObject() )
                    throw new ShexException("A manifest entry is a mapping: " + entry);
                @SuppressWarnings("unchecked")
                Map<String, Object> raw = (Map<String, Object>)toJava(entry);
                Map<String, Object> flat = flattenEntry(raw, scopes);
                settleReferences(flat);
                entries.add(new ManifestEntry(flat));
            }
        }
        return entries;
    }

    /**
     * A document as {@code {"@context": ..., "entries": [...]}}: a deployed classic manifest (a
     * bare list, or one entry, no {@code @context}) is wrapped, a test manifest's {@code shex}
     * read as {@code schema} and {@code title} as {@code name}; a document with an
     * {@code @context} is taken as it says.
     */
    private static JsonObject upgrade(JsonValue doc) {
        JsonObject map;
        if ( doc.isArray() ) {
            map = new JsonObject();
            map.put("entries", doc);
        } else if ( doc.isObject() && !doc.getAsObject().hasKey("entries") && !doc.getAsObject().hasKey("@context") ) {
            map = new JsonObject();
            JsonArray one = new JsonArray();
            one.add(doc);
            map.put("entries", one);
        } else if ( doc.isObject() ) {
            map = doc.getAsObject();
        } else
            throw new ShexException("A manifest is a list of entries or a mapping, not " + doc);
        JsonValue entries = map.get("entries");
        if ( entries != null && entries.isArray() ) {
            for ( JsonValue entry : entries.getAsArray() ) {
                if ( !entry.isObject() )
                    continue;
                JsonObject e = entry.getAsObject();
                if ( e.hasKey("shex") && !e.hasKey("schema") )
                    e.put("schema", e.get("shex"));
                if ( e.hasKey("title") && !e.hasKey("name") )
                    e.put("name", e.get("title"));
            }
        }
        return map;
    }

    /** prefix -> namespace for the {@code <prefix>:parms} scopes an {@code @context} binds. */
    private static Map<String, String> boundScopes(JsonValue context) {
        Map<String, String> scopes = new LinkedHashMap<>();
        if ( context == null )
            return scopes;
        List<JsonValue> parts = context.isArray() ? context.getAsArray() : List.of(context);
        for ( JsonValue part : parts ) {
            if ( !part.isObject() )
                continue;
            JsonObject m = part.getAsObject();
            for ( String key : m.keys() ) {
                JsonValue value = m.get(key);
                if ( value.isString() && m.hasKey(key + ":parms") )
                    scopes.put(key, value.getAsString().value());
            }
        }
        return scopes;
    }

    /** A JSON value as plain Java: String, Boolean, Number, List or LinkedHashMap. */
    static Object toJava(JsonValue value) {
        if ( value == null || value.isNull() )
            return null;
        if ( value.isString() )
            return value.getAsString().value();
        if ( value.isBoolean() )
            return value.getAsBoolean().value();
        if ( value.isNumber() )
            return value.getAsNumber().value();
        if ( value.isArray() ) {
            List<Object> out = new ArrayList<>();
            for ( JsonValue item : value.getAsArray() )
                out.add(toJava(item));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for ( Map.Entry<String, JsonValue> e : value.getAsObject().entrySet() )
            out.put(e.getKey(), toJava(e.getValue()));
        return out;
    }

    /**
     * The entry with each {@code <prefix>:parms} scope's attributes beside the core ones, the
     * scopes' {@code pluginURL} collected under {@code plugins} and kept under {@code parms} by
     * namespace. One name given twice with different values is refused, as shex.js refuses it.
     */
    private static Map<String, Object> flattenEntry(Map<String, Object> entry, Map<String, String> scopes) {
        Map<String, Object> flat = new LinkedHashMap<>();
        List<Object> plugins = new ArrayList<>();
        Map<String, Object> parms = new LinkedHashMap<>();
        for ( Map.Entry<String, Object> e : entry.entrySet() ) {          // the core attributes first ...
            String key = e.getKey();
            if ( key.endsWith(":parms") && e.getValue() instanceof Map )
                continue;
            if ( key.equals("pluginURL") || key.equals("plugins") )
                plugins.addAll(asList(e.getValue()));
            else
                flat.put(key, e.getValue());
        }
        for ( Map.Entry<String, Object> e : entry.entrySet() ) {          // ... then each scope's, beside them
            String key = e.getKey();
            if ( !(key.endsWith(":parms") && e.getValue() instanceof Map) )
                continue;
            String prefix = key.substring(0, key.length() - ":parms".length());
            String ns = scopes.getOrDefault(prefix, prefix + ":");
            Map<String, Object> kept = new LinkedHashMap<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> scope = (Map<String, Object>)e.getValue();
            for ( Map.Entry<String, Object> s : scope.entrySet() ) {
                if ( s.getKey().equals("pluginURL") ) {
                    plugins.addAll(asList(s.getValue()));
                    continue;
                }
                Object already = flat.get(s.getKey());
                if ( already != null && !Objects.equals(already, s.getValue()) )
                    throw new ShexException("Manifest entry " + labelOf(entry) + ": " + s.getKey()
                                            + " is given twice, " + already + " and " + s.getValue() + " in " + key);
                flat.put(s.getKey(), s.getValue());
                kept.put(s.getKey(), s.getValue());
            }
            parms.put(ns, kept);
        }
        if ( !plugins.isEmpty() )
            flat.put("plugins", plugins);
        if ( !parms.isEmpty() )
            flat.put("parms", parms);
        return flat;
    }

    private static String labelOf(Map<String, Object> entry) {
        return new ManifestEntry(entry).label();
    }

    private static List<Object> asList(Object value) {
        if ( value instanceof List<?> list ) {
            List<Object> out = new ArrayList<>();
            out.addAll(list);
            return out;
        }
        List<Object> out = new ArrayList<>();
        out.add(value);
        return out;
    }

    /** The heuristic that tells a document's text from a reference to it: a reference has no whitespace and no bracket or quote. */
    public static boolean looksLikeText(String value) {
        for ( int i = 0 ; i < value.length() ; i++ ) {
            char c = value.charAt(i);
            if ( Character.isWhitespace(c) || "<>{}[]\"".indexOf(c) >= 0 )
                return true;
        }
        return false;
    }

    /**
     * Rename each {@code schema} / {@code data} / {@code queryMap} whose value is a reference to
     * its {@code ...URL} spelling; {@code data} may name several documents, each settling on its own.
     */
    private static void settleReferences(Map<String, Object> entry) {
        for ( String key : DOCUMENTS ) {
            if ( !entry.containsKey(key) )
                continue;
            Object value = entry.remove(key);
            boolean single = !(value instanceof List);
            List<Object> refs = new ArrayList<>();
            List<Object> texts = new ArrayList<>();
            for ( Object v : asList(value) ) {
                if ( v instanceof String s && !looksLikeText(s) )
                    refs.add(v);
                else
                    texts.add(v);
            }
            if ( !refs.isEmpty() )
                entry.put(key + "URL", single ? refs.get(0) : refs);
            if ( !texts.isEmpty() )
                entry.put(key, single ? texts.get(0) : texts);
        }
    }

    // -- references -----------------------------------------------------------------------------

    /**
     * The representations a reference without an extension may be served as, in the order a
     * reader prefers them: as written (a server may negotiate), then {@code .shex},
     * {@code .json} and {@code .ttl}. A reference with an extension is just itself.
     */
    public static List<String> representations(String reference) {
        String last = reference.substring(reference.lastIndexOf('/') + 1);
        if ( last.isEmpty() || last.contains(".") || last.contains("?") || last.contains("#") )
            return List.of(reference);
        List<String> out = new ArrayList<>();
        out.add(reference);
        for ( String ext : REPRESENTATIONS )
            out.add(reference + ext);
        return out;
    }

    // -- the RDF reading ---------------------------------------------------------------------

    /** A vocabulary term: how a predicate is spelled for a text object and for an IRI object. */
    private record VocabTerm(String text, String url, boolean isList) {
        String keyFor(boolean objectIsIri) {
            if ( objectIsIri )
                return url != null ? url : text;
            return text != null ? text : url;
        }
    }

    /** The two core contexts, shex-manifest.jsonld and shex.js's, by predicate IRI. */
    private static VocabTerm coreTerm(String predicate) {
        if ( predicate.equals(RDFS_COMMENT) )
            return new VocabTerm("comment", null, false);
        if ( predicate.startsWith(SHEXMAN) ) {
            switch ( predicate.substring(SHEXMAN.length()) ) {
                case "entries" :       return new VocabTerm(null, "entries", true);
                case "name" :          return new VocabTerm("name", null, false);
                case "schema" :        return new VocabTerm("schema", "schemaURL", false);
                case "schemaLabel" :   return new VocabTerm("schemaLabel", null, false);
                case "data" :          return new VocabTerm("data", "dataURL", false);
                case "dataLabel" :     return new VocabTerm("dataLabel", null, false);
                case "queryMap" :      return new VocabTerm("queryMap", "queryMapURL", false);
                case "queryMapLabel" : return new VocabTerm("queryMapLabel", null, false);
                default :              return null;
            }
        }
        if ( predicate.startsWith(SHEX) ) {
            switch ( predicate.substring(SHEX.length()) ) {
                case "node" :   return new VocabTerm(null, "node", false);
                case "shape" :  return new VocabTerm(null, "shape", false);
                case "status" : return new VocabTerm("status", null, false);
                default :       return null;
            }
        }
        if ( predicate.startsWith(SHEXJS) ) {
            switch ( predicate.substring(SHEXJS.length()) ) {
                case "neighborhood" :    return new VocabTerm("neighborhood", null, false);
                case "plugin" :          return new VocabTerm(null, "pluginURL", false);
                case "manifestContext" : return new VocabTerm(null, "manifestContext", false);
                case "dataBase" :        return new VocabTerm(null, "dataBase", false);
                case "slurp" :           return new VocabTerm("slurp", null, false);
                case "explain" :         return new VocabTerm("explain", null, false);
                case "regexpEngine" :    return new VocabTerm("regexpEngine", null, false);
                case "meta" :            return new VocabTerm("meta", "metaURL", false);
                default :                return null;
            }
        }
        return null;
    }

    /**
     * The entries an RDF graph of a manifest holds: the members of its entries list (or,
     * failing a list, every node with a schema), each read back by predicate.
     */
    public static List<ManifestEntry> readGraph(Graph graph) {
        List<Node> nodes = new ArrayList<>();
        Node entries = NodeFactory.createURI(SHEXMAN + "entries");
        Iterator<Triple> heads = graph.find(Node.ANY, entries, Node.ANY);
        while ( heads.hasNext() )
            nodes.addAll(G.rdfList(graph, heads.next().getObject()));
        if ( nodes.isEmpty() ) {
            Node schema = NodeFactory.createURI(SHEXMAN + "schema");
            Iterator<Triple> withSchema = graph.find(Node.ANY, schema, Node.ANY);
            while ( withSchema.hasNext() ) {
                Node subject = withSchema.next().getSubject();
                if ( !nodes.contains(subject) )
                    nodes.add(subject);
            }
        }
        List<ManifestEntry> out = new ArrayList<>();
        for ( Node node : nodes )
            out.add(entryFromNode(graph, node));
        return out;
    }

    private static Object literalValue(Node literal) {
        String lexical = literal.getLiteralLexicalForm();
        String datatype = literal.getLiteralDatatypeURI();
        if ( datatype == null )
            return lexical;
        if ( datatype.equals(XSD_NS + "boolean") )
            return lexical.equalsIgnoreCase("true") || lexical.equals("1");
        if ( datatype.equals(XSD_NS + "integer") || datatype.equals(XSD_NS + "int") || datatype.equals(XSD_NS + "long") ) {
            try { return Long.parseLong(lexical); } catch (NumberFormatException ex) { return lexical; }
        }
        if ( datatype.equals(XSD_NS + "decimal") || datatype.equals(XSD_NS + "double") || datatype.equals(XSD_NS + "float") ) {
            try { return Double.parseDouble(lexical); } catch (NumberFormatException ex) { return lexical; }
        }
        return lexical;
    }

    /** a key's value, one or several */
    private static void put(Map<String, Object> map, String key, Object value) {
        Object existing = map.get(key);
        if ( existing == null )
            map.put(key, value);
        else if ( existing instanceof List<?> ) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>)existing;
            list.add(value);
        } else {
            List<Object> list = new ArrayList<>();
            list.add(existing);
            list.add(value);
            map.put(key, list);
        }
    }

    /** a literal as itself; an IRI as its string; a blank node as the mapping of its predicates */
    private static Object valueOf(Graph graph, Node node) {
        if ( node.isLiteral() )
            return literalValue(node);
        if ( node.isURI() )
            return node.getURI();
        Map<String, Object> out = new LinkedHashMap<>();
        for ( Triple t : predicateObjects(graph, node) ) {
            String predicate = t.getPredicate().getURI();
            Node object = t.getObject();
            VocabTerm term = coreTerm(predicate);
            if ( term == null )
                put(out, predicate, valueOf(graph, object));
            else if ( term.isList() )
                out.put(term.keyFor(object.isURI()), listValues(graph, object));
            else
                put(out, term.keyFor(object.isURI()), valueOf(graph, object));
        }
        return out;
    }

    private static List<Object> listValues(Graph graph, Node head) {
        List<Object> out = new ArrayList<>();
        if ( head.equals(RDF.nil.asNode()) )
            return out;
        for ( Node member : G.rdfList(graph, head) )
            out.add(valueOf(graph, member));
        return out;
    }

    /** the node's triples, by predicate then object, for a stable reading */
    private static List<Triple> predicateObjects(Graph graph, Node subject) {
        List<Triple> triples = new ArrayList<>();
        Iterator<Triple> it = graph.find(subject, Node.ANY, Node.ANY);
        while ( it.hasNext() )
            triples.add(it.next());
        triples.sort((a, b) -> {
            int c = a.getPredicate().getURI().compareTo(b.getPredicate().getURI());
            return c != 0 ? c : a.getObject().toString().compareTo(b.getObject().toString());
        });
        return triples;
    }

    private static ManifestEntry entryFromNode(Graph graph, Node node) {
        Map<String, Object> flat = new LinkedHashMap<>();
        List<Object> plugins = new ArrayList<>();
        Map<String, Object> parms = new LinkedHashMap<>();
        if ( node.isURI() )
            flat.put("@id", node.getURI());
        String pluginPredicate = SHEXJS + "plugin";
        for ( Triple t : predicateObjects(graph, node) ) {
            String predicate = t.getPredicate().getURI();
            Node object = t.getObject();
            VocabTerm term = coreTerm(predicate);
            if ( term != null ) {
                String key = term.keyFor(object.isURI());
                if ( term.isList() ) {
                    List<Object> items = listValues(graph, object);
                    if ( key.equals("pluginURL") )
                        plugins.addAll(items);
                    else
                        flat.put(key, items);
                } else if ( key.equals("pluginURL") )
                    plugins.add(valueOf(graph, object));
                else
                    put(flat, key, valueOf(graph, object));
            } else if ( predicate.endsWith("parms") && object.isBlank() ) {
                String ns = predicate.substring(0, predicate.length() - "parms".length());
                Map<String, Object> scope = new LinkedHashMap<>();
                for ( Triple t2 : predicateObjects(graph, object) ) {
                    String p2 = t2.getPredicate().getURI();
                    Node o2 = t2.getObject();
                    if ( p2.equals(pluginPredicate) ) {
                        plugins.add(valueOf(graph, o2));
                        continue;
                    }
                    VocabTerm known = coreTerm(p2);
                    String local = p2.startsWith(ns) ? p2.substring(ns.length()) : p2;
                    String name = known != null ? known.keyFor(o2.isURI()) : (o2.isURI() ? local + "URL" : local);
                    if ( known != null && known.isList() )
                        scope.put(name, listValues(graph, o2));
                    else
                        put(scope, name, valueOf(graph, o2));
                }
                for ( Map.Entry<String, Object> s : scope.entrySet() ) {
                    Object already = flat.get(s.getKey());
                    if ( already != null && !Objects.equals(already, s.getValue()) )
                        throw new ShexException("Manifest entry " + node + ": " + s.getKey()
                                                + " is given twice, " + already + " and " + s.getValue() + " in " + predicate);
                    flat.put(s.getKey(), s.getValue());
                }
                parms.put(ns, scope);
            } else
                put(flat, predicate, valueOf(graph, object));
        }
        if ( !plugins.isEmpty() )
            flat.put("plugins", plugins);
        if ( !parms.isEmpty() )
            flat.put("parms", parms);
        return new ManifestEntry(flat);
    }
}
