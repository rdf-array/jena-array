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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonString;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.XSD;

/** RDF terms as ShExMap sees them: the JSON forms shared with shex.js and PyShEx, N3 forms
 * for keys and digests, and the parsing of terms given on a command line. */
public final class Terms {
    private Terms() {}

    public static final String XSD_STRING = XSD.xstring.getURI();

    /** Whether a term can be the subject of a triple. */
    public static boolean isResource(Node n) {
        return n.isURI() || n.isBlank();
    }

    /** A term in N3: {@code <iri>}, {@code _:label}, {@code "lexical"}, {@code "lexical"@lang} or
     * {@code "lexical"^^<datatype>}. */
    public static String n3(Node n) {
        if (n.isURI())
            return "<" + n.getURI() + ">";
        if (n.isBlank())
            return "_:" + n.getBlankNodeLabel();
        if (n.isLiteral()) {
            String lex = escape(n.getLiteralLexicalForm());
            String lang = n.getLiteralLanguage();
            if (lang != null && !lang.isEmpty())
                return "\"" + lex + "\"@" + lang;
            String dt = n.getLiteralDatatypeURI();
            if (dt != null && !dt.equals(XSD_STRING))
                return "\"" + lex + "\"^^<" + dt + ">";
            return "\"" + lex + "\"";
        }
        return n.toString();
    }

    /** The lexical form of a bound value, as the functions see it. */
    public static String lexical(Node n) {
        if (n.isURI())
            return n.getURI();
        if (n.isBlank())
            return n.getBlankNodeLabel();
        if (n.isLiteral())
            return n.getLiteralLexicalForm();
        return n.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char d = s.charAt(++i);
                switch (d) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    default: out.append(d);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** A term as shex.js's bindings JSON writes it: a literal as {@code {"value", "type"?, "language"?}},
     * a blank node as {@code "_:label"}, an IRI as a string. */
    public static JsonValue toJson(Node n) {
        if (n.isURI())
            return new JsonString(n.getURI());
        if (n.isBlank())
            return new JsonString("_:" + n.getBlankNodeLabel());
        if (n.isLiteral()) {
            JsonObject out = new JsonObject();
            out.put("value", n.getLiteralLexicalForm());
            String lang = n.getLiteralLanguage();
            String dt = n.getLiteralDatatypeURI();
            if (lang != null && !lang.isEmpty())
                out.put("language", lang);
            else if (dt != null && !dt.equals(XSD_STRING))
                out.put("type", dt);
            return out;
        }
        return new JsonString(n.toString());
    }

    /** The inverse of {@link #toJson}; also reads a quoted N3 literal string. */
    public static Node fromJson(JsonValue v) {
        if (v.isObject()) {
            JsonObject o = v.getAsObject();
            JsonValue value = o.get("value");
            if (value == null || !value.isString())
                throw new BindingTreeException("a literal needs a \"value\": " + v);
            String lang = o.hasKey("language") && o.get("language").isString() ? o.get("language").getAsString().value() : null;
            String dt = o.hasKey("type") && o.get("type").isString() ? o.get("type").getAsString().value() : null;
            return literal(value.getAsString().value(), lang, dt);
        }
        if (v.isString())
            return parseTerm(v.getAsString().value());
        throw new BindingTreeException("not a ShExMap bound term: " + v);
    }

    /** A term from its string form: {@code _:label}, {@code <iri>}, {@code "literal"}, or a bare IRI. */
    public static Node parseTerm(String s) {
        s = s.strip();
        if (s.startsWith("_:"))
            return NodeFactory.createBlankNode(s.substring(2));
        if (s.startsWith("\""))
            return n3Literal(s);
        if (s.startsWith("<") && s.endsWith(">"))
            return NodeFactory.createURI(s.substring(1, s.length() - 1));
        return NodeFactory.createURI(s);
    }

    private static Node n3Literal(String s) {
        int close = s.lastIndexOf('"');
        if (close <= 0)
            throw new BindingTreeException("not a quoted literal: " + s);
        String lex = unescape(s.substring(1, close));
        String rest = s.substring(close + 1);
        if (rest.isEmpty())
            return literal(lex, null, null);
        if (rest.startsWith("@"))
            return literal(lex, rest.substring(1), null);
        if (rest.startsWith("^^")) {
            String dt = rest.substring(2);
            if (dt.startsWith("<") && dt.endsWith(">"))
                dt = dt.substring(1, dt.length() - 1);
            return literal(lex, null, dt);
        }
        throw new BindingTreeException("not a quoted literal: " + s);
    }

    /** A literal from its parts. */
    public static Node literal(String lex, String lang, String datatype) {
        if (lang != null && !lang.isEmpty())
            return NodeFactory.createLiteralLang(lex, lang);
        if (datatype != null && !datatype.equals(XSD_STRING))
            return NodeFactory.createLiteral(lex, datatype(datatype));
        return NodeFactory.createLiteralString(lex);
    }

    public static RDFDatatype datatype(String uri) {
        return TypeMapper.getInstance().getSafeTypeByName(uri);
    }

    /** A plain literal: neither a language tag nor a datatype other than xsd:string. */
    public static boolean isPlainLiteral(Node n) {
        if (!n.isLiteral())
            return false;
        String lang = n.getLiteralLanguage();
        String dt = n.getLiteralDatatypeURI();
        return (lang == null || lang.isEmpty()) && (dt == null || dt.equals(XSD_STRING));
    }

    /** A short, reproducible digest of some strings (SHA-1, NUL-separated, 16 hex digits); the
     * labels of minted blank nodes and deduplication keys use it. */
    public static String digest(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            boolean first = true;
            for (String p : parts) {
                if (!first)
                    md.update((byte) 0);
                first = false;
                md.update(p.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
                sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
