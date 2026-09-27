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

package org.apache.jena.shexmap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/** ShExMap binding functions: {@code regex(...)}, {@code hashmap(...)} and {@code id(...)}.
 * <p>
 * A {@code %Map:{ ... %}} action is either a variable name ({@code bp:given} or {@code <http://...>}),
 * which binds the matched value, or a function call.  Each function works in two directions, as
 * in shex.js's extension-map: <em>lift</em> runs while validating the input and turns the matched
 * value into bindings; <em>lower</em> runs while materializing the output and turns bindings back
 * into a value.
 * <ul>
 * <li>{@code regex(/(?<bp:family>[a-zA-Z]+), (?<bp:given>[a-zA-Z]+)/)} -- lift: match the value and
 *     bind each named group to the variable it names; lower: replace each named group with its
 *     variable's value.</li>
 * <li>{@code hashmap(bp:status, {"D": "Divorced", "M": "Married"})} -- lift: bind the variable to the
 *     map's value for the matched key; lower: find the key whose value the variable holds.</li>
 * <li>{@code id(bp:mrn)}, {@code id(@node)}, {@code id(<http://a.example/person/{bp:mrn}>)} -- names the
 *     node a shape-valued constraint builds (see {@link Materializer}); it binds nothing when
 *     validating input, so one schema serves both directions.</li>
 * </ul>
 */
public final class MapFunctions {
    private MapFunctions() {}

    /** In {@code id()}: the input node the enclosing iteration matched. */
    public static final String NODE_ARGUMENT = "@node";

    /** The namespace {@link #anyPrefixes} gives an alias: a variable expanded with it is not a real IRI. */
    public static final String PLACEHOLDER_NAMESPACE = "urn:shexmap-prefix:";

    private static final Pattern VARIABLE = Pattern.compile("^ *(?:<([^>]*)>|([^:<>\\s]*):(\\S*)) *$");
    private static final Pattern FUNCTION_CALL = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9]*)\\s*\\((.*)\\)\\s*$", Pattern.DOTALL);
    private static final String CAPTURE_NAME_RE = "\\?<((?:[A-Za-z0-9_.\\-]*:[^>\\s]*)|(?:<[^>]+>))>";
    private static final Pattern CAPTURE_NAME = Pattern.compile(CAPTURE_NAME_RE);
    private static final Pattern CAPTURE_GROUP = Pattern.compile("\\(" + CAPTURE_NAME_RE + "[^)]+\\)");
    private static final Pattern REGEX_BODY = Pattern.compile("^\\s*/(.*)/\\s*$", Pattern.DOTALL);
    private static final Pattern HASHMAP_ARGS = Pattern.compile("^\\s*([\\w:<>/#.\\-]+)\\s*,\\s*(\\{.*\\})\\s*$", Pattern.DOTALL);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern ESCAPED_META = Pattern.compile("\\\\([/^$])");
    private static final Pattern ALIAS = Pattern.compile("([A-Za-z0-9_.\\-]*):");

    /** Expand a ShExMap variable name -- {@code prefix:local} or {@code <iri>} -- to an IRI string. */
    public static String expandVariable(String name, Map<String, String> prefixes) {
        Matcher m = VARIABLE.matcher(name);
        if (!m.matches())
            throw new MapFunctionException("\"" + name + "\" is not a ShExMap variable (prefix:name or <iri>)");
        if (m.group(1) != null)
            return m.group(1);
        String ns = prefixes.get(m.group(2));
        if (ns == null)
            throw new MapFunctionException("Unknown prefix \"" + m.group(2) + ":\" in ShExMap variable \"" + name + "\"");
        return ns + m.group(3);
    }

    /** Whether a Map code is a function call rather than a variable name. */
    public static boolean isFunctionCall(String code) {
        return FUNCTION_CALL.matcher(code).matches();
    }

    /** Whether a Map code is an {@code id(...)} call. */
    public static boolean isKeyCode(String code) {
        Matcher m = FUNCTION_CALL.matcher(code);
        return m.matches() && m.group(1).equals("id");
    }

    private static String[] splitCall(String code) {
        Matcher m = FUNCTION_CALL.matcher(code);
        if (!m.matches())
            throw new MapFunctionException("Invalid ShExMap function call: " + code.strip());
        return new String[] { m.group(1), m.group(2) };
    }

    private static Node plain(String s) {
        return NodeFactory.createLiteralString(s);
    }

    private static String unescapeMeta(String s) {
        return ESCAPED_META.matcher(s).replaceAll("$1");
    }

    private static String collapseSpaces(String s) {
        return s.replaceAll("  +", " ");
    }

    // -- regex ------------------------------------------------------------------------------

    private static String regexBody(String code, String args) {
        Matcher m = REGEX_BODY.matcher(args);
        if (m.matches()) {
            if (m.group(1).isEmpty())
                throw new MapFunctionException(code.strip() + " is missing the required regex pattern");
            return m.group(1);
        }
        return args;
    }

    /** The named groups of a regex code, in order, expanded; and the pattern with the names
     * removed (Java group names cannot hold {@code :}). */
    private static Object[] regexParts(String code, String args, Map<String, String> prefixes) {
        String pattern = regexBody(code, args);
        List<String> names = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        Matcher m = CAPTURE_NAME.matcher(pattern);
        int pos = 0;
        while (m.find()) {
            plain.append(pattern, pos, m.start());
            names.add(expandVariable(unescapeMeta(m.group(1)), prefixes));
            pos = m.end();
        }
        plain.append(pattern.substring(pos));
        if (names.isEmpty())
            throw new MapFunctionException("Found no capture variable in " + code.strip());
        return new Object[] { names, plain.toString() };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Node> regexLift(String code, String args, Node value, Map<String, String> prefixes) {
        Object[] parts = regexParts(code, args, prefixes);
        List<String> names = (List<String>) parts[0];
        String text = Terms.lexical(value);
        Matcher found;
        try {
            found = Pattern.compile((String) parts[1]).matcher(text);
        } catch (PatternSyntaxException e) {
            throw new MapFunctionException("Error matching " + code.strip() + " against \"" + text + "\": " + e.getMessage());
        }
        if (!found.find())
            throw new MapFunctionException(code.strip() + " found no match for input \"" + text + "\"");
        Map<String, Node> out = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String g = found.group(i + 1);
            out.put(names.get(i), plain(g == null ? "" : g));
        }
        return out;
    }

    private static Node regexLower(String code, String args, Function<String, Node> get, Map<String, String> prefixes) {
        String pattern = regexBody(code, args);
        boolean matched = false;
        boolean missing = false;
        StringBuilder text = new StringBuilder();
        Matcher m = CAPTURE_GROUP.matcher(pattern);
        int pos = 0;
        while (m.find()) {
            matched = true;
            text.append(pattern, pos, m.start());
            Node v = get.apply(expandVariable(unescapeMeta(m.group(1)), prefixes));
            if (v == null)
                missing = true;
            else
                text.append(Terms.lexical(v));
            pos = m.end();
        }
        text.append(pattern.substring(pos));
        if (!matched)
            throw new MapFunctionException("Found no capture variable in " + code.strip());
        if (missing)
            return null;
        return plain(unescapeMeta(collapseSpaces(text.toString())));
    }

    // -- hashmap ----------------------------------------------------------------------------

    private static Object[] hashmapArgs(String code, String args, Map<String, String> prefixes) {
        Matcher m = HASHMAP_ARGS.matcher(args);
        if (!m.matches())
            throw new MapFunctionException("hashmap needs a variable name and a JSON map, found: " + code.strip());
        JsonValue parsed;
        try {
            parsed = JSON.parseAny(m.group(2));
        } catch (JsonException e) {
            throw new MapFunctionException("hashmap could not parse the map in " + code.strip() + ": " + e.getMessage());
        }
        if (!parsed.isObject() || parsed.getAsObject().keys().isEmpty())
            throw new MapFunctionException("hashmap needs a non-empty JSON object in " + code.strip());
        JsonObject obj = parsed.getAsObject();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String k : obj.keys()) {
            JsonValue v = obj.get(k);
            mapping.put(k, v.isString() ? v.getAsString().value() : v.toString());
        }
        if (new HashSet<>(mapping.values()).size() != mapping.size())
            throw new MapFunctionException("hashmap values must be unique so the map can be inverted: " + code.strip());
        return new Object[] { expandVariable(m.group(1), prefixes), mapping };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Node> hashmapLift(String code, String args, Node value, Map<String, String> prefixes) {
        Object[] parts = hashmapArgs(code, args, prefixes);
        Map<String, String> mapping = (Map<String, String>) parts[1];
        String key = Terms.lexical(value);
        if (!mapping.containsKey(key))
            throw new MapFunctionException(code.strip() + " has no entry for \"" + key + "\"");
        Map<String, Node> out = new LinkedHashMap<>();
        out.put((String) parts[0], plain(mapping.get(key)));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Node hashmapLower(String code, String args, Function<String, Node> get, Map<String, String> prefixes) {
        Object[] parts = hashmapArgs(code, args, prefixes);
        Map<String, String> mapping = (Map<String, String>) parts[1];
        Node val = get.apply((String) parts[0]);
        if (val == null)
            return null;
        String text = Terms.lexical(val);
        for (Map.Entry<String, String> e : mapping.entrySet())
            if (e.getValue().equals(text))
                return plain(e.getKey());
        throw new MapFunctionException(code.strip() + " cannot invert \"" + text + "\"");
    }

    // -- id ---------------------------------------------------------------------------------

    /** An argument of {@code id()}. */
    public sealed interface KeyTerm permits Variable, NodeArgument, Template {}

    /** A variable, by IRI. */
    public record Variable(String iri) implements KeyTerm {}

    /** {@link #NODE_ARGUMENT}: the node the enclosing input iteration matched. */
    public record NodeArgument() implements KeyTerm {}

    /** An IRI template argument of {@code id()}: {@code <http://a.example/person/{v:mrn}>}. */
    public static final class Template implements KeyTerm {
        private final List<Object> segments = new ArrayList<>();   // String, or Variable
        private final String text;

        public Template(String text, Map<String, String> prefixes) {
            this.text = text;
            Matcher m = PLACEHOLDER.matcher(text);
            int pos = 0;
            while (m.find()) {
                if (m.start() > pos)
                    segments.add(text.substring(pos, m.start()));
                segments.add(new Variable(expandVariable(m.group(1), prefixes)));
                pos = m.end();
            }
            if (pos < text.length())
                segments.add(text.substring(pos));
        }

        /** The variables the template reads. */
        public List<String> variables() {
            List<String> out = new ArrayList<>();
            for (Object s : segments)
                if (s instanceof Variable v)
                    out.add(v.iri());
            return out;
        }

        /** The IRI, values percent-encoded as R2RML does; null when a variable is unbound. */
        public Node expand(Function<String, Node> get) {
            StringBuilder out = new StringBuilder();
            for (Object s : segments) {
                if (s instanceof Variable v) {
                    Node value = get.apply(v.iri());
                    if (value == null)
                        return null;
                    out.append(percentEncode(Terms.lexical(value)));
                } else {
                    out.append((String) s);
                }
            }
            return NodeFactory.createURI(out.toString());
        }

        public String text() { return text; }

        @Override public String toString() { return "Template(<" + text + ">)"; }
    }

    /** Percent-encode everything but unreserved characters (letters, digits, {@code -._~}), as
     * R2RML's IRI-safe templates do. */
    public static String percentEncode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~')
                out.append((char) c);
            else
                out.append(String.format("%%%02X", c));
        }
        return out.toString();
    }

    /** The arguments of an {@code id(...)} code. */
    public static List<KeyTerm> keyTerms(String code, Map<String, String> prefixes) {
        String[] call = splitCall(code);
        if (!call[0].equals("id"))
            throw new MapFunctionException(code.strip() + " is not an id() code");
        List<KeyTerm> terms = new ArrayList<>();
        if (!call[1].isBlank())
            for (String a : call[1].split(",")) {
                a = a.strip();
                if (a.equals(NODE_ARGUMENT))
                    terms.add(new NodeArgument());
                else if (a.startsWith("<") && a.endsWith(">") && a.contains("{"))
                    terms.add(new Template(a.substring(1, a.length() - 1), prefixes));
                else
                    terms.add(new Variable(expandVariable(a, prefixes)));
            }
        if (terms.isEmpty())
            throw new MapFunctionException("id() needs at least one variable, template or " + NODE_ARGUMENT + ": " + code.strip());
        return terms;
    }

    /** The variables an {@code id(...)} code reads (templates' placeholders included), and
     * {@link #NODE_ARGUMENT} where it appears. */
    public static List<String> keyArguments(String code, Map<String, String> prefixes) {
        List<String> out = new ArrayList<>();
        for (KeyTerm t : keyTerms(code, prefixes)) {
            if (t instanceof Variable v)
                out.add(v.iri());
            else if (t instanceof NodeArgument)
                out.add(NODE_ARGUMENT);
            else if (t instanceof Template tpl)
                out.addAll(tpl.variables());
        }
        return out;
    }

    // -- dispatch -----------------------------------------------------------------------------

    /** Apply a function call to a matched value, returning the variable bindings it makes. */
    public static Map<String, Node> lift(String code, Node value, Map<String, String> prefixes) {
        String[] call = splitCall(code);
        switch (call[0]) {
            case "regex": return regexLift(code, call[1], value, prefixes);
            case "hashmap": return hashmapLift(code, call[1], value, prefixes);
            case "id":
                keyArguments(code, prefixes);   // id() names an output node; validating input it binds nothing
                return new LinkedHashMap<>();
            default:
                throw new MapFunctionException("Unknown ShExMap function " + call[0] + "() in " + code.strip());
        }
    }

    /** The variables a function call reads when lowering (and binds when lifting). */
    @SuppressWarnings("unchecked")
    public static List<String> variablesOf(String code, Map<String, String> prefixes) {
        String[] call = splitCall(code);
        switch (call[0]) {
            case "regex": return (List<String>) regexParts(code, call[1], prefixes)[0];
            case "hashmap": return List.of((String) hashmapArgs(code, call[1], prefixes)[0]);
            case "id": {
                List<String> out = new ArrayList<>(keyArguments(code, prefixes));
                out.removeIf(NODE_ARGUMENT::equals);
                return out;
            }
            default:
                throw new MapFunctionException("Unknown ShExMap function " + call[0] + "() in " + code.strip());
        }
    }

    /** Build a value from bindings with a function call; null when a variable is unbound. */
    public static Node lower(String code, Function<String, Node> get, Map<String, String> prefixes) {
        String[] call = splitCall(code);
        switch (call[0]) {
            case "regex": return regexLower(code, call[1], get, prefixes);
            case "hashmap": return hashmapLower(code, call[1], get, prefixes);
            case "id":
                throw new MapFunctionException(code.strip() + " names a node; it belongs on a shape-valued constraint, not a value");
            default:
                throw new MapFunctionException("Unknown ShExMap function " + call[0] + "() in " + code.strip());
        }
    }

    /** The variables a Map code binds or reads: the variable itself, or a function's. */
    public static List<String> codeVariables(String code, Map<String, String> prefixes) {
        return isFunctionCall(code) ? variablesOf(code, prefixes) : List.of(expandVariable(code, prefixes));
    }

    /** Prefixes that expand any alias a code mentions, for checking codes when the declarations
     * are not at hand (the validator's semantic-action plugin): each alias maps to a placeholder
     * namespace. */
    public static Map<String, String> anyPrefixes(String code) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = ALIAS.matcher(code);
        while (m.find())
            out.putIfAbsent(m.group(1), PLACEHOLDER_NAMESPACE + m.group(1) + ":");
        return out;
    }

    /** Whether a prefix map (see {@link #anyPrefixes}) expanded a variable to a placeholder. */
    public static boolean isPlaceholder(String iri) {
        return iri.startsWith(PLACEHOLDER_NAMESPACE);
    }

    /** Distinct values as a set, for callers that check uniqueness. */
    static Set<String> distinct(List<String> values) {
        return new HashSet<>(values);
    }
}
