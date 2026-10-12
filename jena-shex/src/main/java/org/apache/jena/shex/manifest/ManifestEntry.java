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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry of a ShEx examples manifest -- the format shex.js reads -- in its flat shape:
 * every attribute as the reader found it, under its JSON spelling ({@code schemaURL},
 * {@code data}, {@code node}, ...), with accessors for the ones a validation uses.
 * Values are {@code String}, {@code Boolean}, {@code Number}, {@code List} or {@code Map}.
 */
public class ManifestEntry {
    private final Map<String, Object> attributes;

    public ManifestEntry(Map<String, Object> attributes) {
        this.attributes = new LinkedHashMap<>(attributes);
    }

    /** The attributes as read, read-only. */
    public Map<String, Object> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public Object get(String key) {
        return attributes.get(key);
    }

    public boolean has(String key) {
        return attributes.containsKey(key);
    }

    /** A string attribute; a list's first member; null when absent. */
    public String string(String key) {
        List<String> values = strings(key);
        return values.isEmpty() ? null : values.get(0);
    }

    /** A string attribute, or a list of them, as a list (a number or a boolean as its text). */
    public List<String> strings(String key) {
        Object value = attributes.get(key);
        if ( value == null || value instanceof Map )
            return List.of();
        if ( value instanceof List<?> list ) {
            List<String> out = new ArrayList<>();
            for ( Object item : list ) {
                if ( item != null && !(item instanceof List) && !(item instanceof Map) )
                    out.add(item.toString());
            }
            return out;
        }
        return List.of(value.toString());
    }

    /** The entry's name, else {@code schemaLabel / dataLabel}, as shex.js names a test. */
    public String label() {
        String name = string("name");
        if ( name != null && !name.isEmpty() )
            return name;
        String schemaLabel = schemaLabel();
        String dataLabel = dataLabel();
        return (schemaLabel == null ? "" : schemaLabel) + " / " + (dataLabel == null ? "" : dataLabel);
    }

    public String schemaLabel()        { return string("schemaLabel"); }
    public String dataLabel()          { return string("dataLabel"); }
    /** The schema's text, when the entry carries it. */
    public String schemaText()         { return string("schema"); }
    /** The schema's reference, as written. */
    public String schemaURL()          { return string("schemaURL"); }
    /** The data documents' texts, when the entry carries them. */
    public List<String> dataTexts()    { return strings("data"); }
    /** The data documents' references, as written. */
    public List<String> dataURLs()     { return strings("dataURL"); }
    public String queryMap()           { return string("queryMap"); }
    public String queryMapURL()        { return string("queryMapURL"); }
    /** The nodes of the one-pair form ({@code shex:node}), as written. */
    public List<String> nodes()        { return strings("node"); }
    /** The shape of the one-pair form ({@code shex:shape}), as written; START when absent. */
    public String shape()              { return string("shape"); }
    /** The verdict expected: {@code conformant}, {@code nonconformant}, or null. */
    public String status()             { return string("status"); }
    public String comment()            { return string("comment"); }
    /** shex.js's {@code dataBase}: what the data's relative IRIs, and a query map's nodes, resolve against. */
    public String dataBase()           { return string("dataBase"); }
    public List<String> plugins()      { return strings("plugins"); }

    @Override
    public String toString() {
        return "ManifestEntry[" + label() + "]";
    }
}
