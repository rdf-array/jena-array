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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.lib.IRILib;
import org.apache.jena.shex.ShexException;
import org.apache.jena.shex.manifest.ManifestRunner.EntryRun;
import org.apache.jena.shex.manifest.ManifestRunner.Outcome;
import org.junit.Test;

/**
 * The ShEx examples manifest format shex.js reads: the same three entries as YAML-LD, classic
 * JSON and Turtle, and the all-RDF document -- one Turtle file that is the manifest, the schema
 * (as ShExR, the node {@code <#schema>}) and the data -- shared with shex.js and PyShEx.
 */
public class TestShexManifest {
    private static final String DIR = "src/test/files/manifests/";
    private static final String ALL_RDF = DIR + "manifest-clinObs-allRdf.ttl";
    private static final String[] FORMS = { "clinObs-manifest.yaml", "clinObs-manifest.json", "clinObs-manifest.ttl" };

    @Test public void three_representations_carry_the_same_entries() {
        for ( String form : FORMS ) {
            List<ManifestEntry> entries = ManifestReader.read(DIR + form);
            assertEquals(form, 3, entries.size());
            assertEquals(form, List.of("the least an Observation can be", "the same measurement, in two documents", "no subject"),
                         entries.stream().map(ManifestEntry::dataLabel).toList());
            assertEquals(form, List.of("conformant", "conformant", "nonconformant"),
                         entries.stream().map(ManifestEntry::status).toList());
            ManifestEntry least = entries.get(0), two = entries.get(1), none = entries.get(2);
            assertEquals(form, "clinical observation", least.schemaLabel());
            assertTrue(form, least.schemaURL().endsWith("ClinObs.shex"));
            assertTrue(form + ": text, not a reference", least.dataTexts().get(0).startsWith("PREFIX : <http://hl7.org/fhir/>"));
            assertTrue(form, least.dataURLs().isEmpty());
            assertEquals(form, "<Obs1>@START", least.queryMap().trim());
            assertTrue(form, least.comment().startsWith("The schema's BASE"));
            assertEquals(form, 2, two.dataURLs().size());
            assertTrue(form, two.dataTexts().isEmpty());
            assertEquals(form, "<http://hl7.example/Obs1>@<ObservationShape>", two.queryMap().trim());
            assertTrue(form, none.nodes().get(0).endsWith("Obs1"));
            assertEquals(form, "http://schema.example/ObservationShape", none.shape());
            assertEquals(form, "clinical observation / no subject", none.label());
        }
    }

    @Test public void each_representation_validates_as_its_status_says() {
        for ( String form : FORMS ) {
            List<EntryRun> runs = ManifestRunner.run(DIR + form, null);
            assertEquals(form + ": " + runs.stream().map(r -> r.error).toList(),
                         List.of("conformant", "conformant", "nonconformant"),
                         runs.stream().map(EntryRun::verdict).toList());
            assertTrue(form, runs.stream().allMatch(EntryRun::passed));
        }
    }

    @Test public void the_all_rdf_manifest_is_its_own_schema_and_data() {
        List<EntryRun> runs = ManifestRunner.run(ALL_RDF, null);
        assertEquals(1, runs.size());
        EntryRun run = runs.get(0);
        String url = IRILib.filenameToIRI(ALL_RDF);
        assertEquals("clinical observation / the same measurement, in two documents", run.entry.label());
        assertEquals(url + "#schema", run.entry.schemaURL());
        assertEquals(List.of(url), run.entry.dataURLs());
        assertEquals(List.of("http://hl7.example/Obs1"), run.entry.nodes());
        assertTrue(run.entry.shape().endsWith("/ObservationShape"));
        assertFalse(run.entry.has("queryMap"));
        assertEquals(String.valueOf(run.error), Outcome.CONFORMANT, run.outcome);
        assertTrue(run.passed());
        assertNull(run.error);

        // a node without a subject does not conform, and the entry still expects conformant
        Map<String, Object> attributes = new LinkedHashMap<>(run.entry.attributes());
        attributes.put("node", "http://hl7.example/Practitioner7");
        EntryRun failing = ManifestRunner.runEntry(new ManifestEntry(attributes), url);
        assertEquals(String.valueOf(failing.error), Outcome.NONCONFORMANT, failing.outcome);
        assertFalse(failing.passed());
    }

    @Test public void entries_are_selected_by_label() {
        List<EntryRun> runs = ManifestRunner.run(DIR + "clinObs-manifest.yaml", List.of(Pattern.compile("no subject")));
        assertEquals(1, runs.size());
        assertEquals(Outcome.NONCONFORMANT, runs.get(0).outcome);
        try {
            ManifestRunner.run(ALL_RDF, List.of(Pattern.compile("nothing like this")));
            throw new AssertionError("expected no match");
        } catch (ShexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("No manifest entry matches"));
        }
    }

    @Test public void formats_and_representations() {
        assertEquals(ManifestReader.Format.YAML, ManifestReader.detectFormat("- a: b", null));
        assertEquals(ManifestReader.Format.JSON, ManifestReader.detectFormat("[{}]", null));
        assertEquals(ManifestReader.Format.JSON, ManifestReader.detectFormat("# c\n{\"a\": 1}", null));
        assertEquals(ManifestReader.Format.TURTLE, ManifestReader.detectFormat("PREFIX x: <y>\n", null));
        assertEquals(ManifestReader.Format.TURTLE, ManifestReader.detectFormat("<a> <b> <c> .", null));
        assertEquals(ManifestReader.Format.TURTLE, ManifestReader.detectFormat("anything", "m.ttl"));
        assertEquals(ManifestReader.Format.YAML, ManifestReader.detectFormat("anything", "http://x/m.yaml?v=1"));
        assertEquals(List.of("../schemas/1dot", "../schemas/1dot.shex", "../schemas/1dot.json", "../schemas/1dot.ttl"),
                     ManifestReader.representations("../schemas/1dot"));
        assertEquals(List.of("x.ttl#schema"), ManifestReader.representations("x.ttl#schema"));
        assertEquals(List.of("dir/"), ManifestReader.representations("dir/"));
    }

    @Test public void classic_upgrade_scopes_and_settlement() {
        String json = "[{\"schemaLabel\": \"s\", \"schema\": \"book.shex\", \"dataLabel\": \"d\", \"data\": \"<a> <b> <c> .\","
                      + " \"queryMap\": \"<a>@START\", \"title\": \"t\","
                      + " \"map:parms\": {\"pluginURL\": \"ShExMapPlugin.js\", \"outputSchemaURL\": \"o.shex\", \"staticVars\": {\"k\": \"v\"}}}]";
        List<ManifestEntry> entries = ManifestReader.readJson(JSON.parseAny(json));
        ManifestEntry e = entries.get(0);
        assertEquals("a reference under the text key", "book.shex", e.schemaURL());
        assertNull(e.schemaText());
        assertEquals(List.of("<a> <b> <c> ."), e.dataTexts());
        assertEquals("t", e.string("name"));
        assertEquals("o.shex", e.string("outputSchemaURL"));
        assertEquals(List.of("ShExMapPlugin.js"), e.plugins());
        assertTrue(e.get("parms") instanceof Map);
        String clash = "[{\"schemaURL\": \"s\", \"outputSchemaURL\": \"x\", \"map:parms\": {\"outputSchemaURL\": \"y\"}}]";
        try {
            ManifestReader.readJson(JSON.parseAny(clash));
            throw new AssertionError("expected a refusal");
        } catch (ShexException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("given twice"));
        }
    }

    @Test public void a_turtle_manifest_with_a_scope() {
        String ttl = String.join("\n",
            "PREFIX shexMan: <http://www.w3.org/ns/shex-manifest#>",
            "PREFIX shex: <http://www.w3.org/ns/shex#>",
            "PREFIX shexjs: <https://shex.js.org/doc/manifest-context.jsonld#>",
            "PREFIX map: <http://shex.io/extensions/Map/#>",
            "_:m shexMan:entries ( _:e ) .",
            "_:e shexMan:schema <s.shex> ; shexMan:data \"<a> <b> <c> .\" ; shex:node <a> ; shex:status \"conformant\" ;",
            "    map:parms [ shexjs:plugin <ShExMapPlugin.js> ; map:outputSchema <o.shex> ; map:staticVars [ map:k \"v\" ] ] ;",
            "    shexjs:dataBase <http://d.example/> ; shexjs:slurp true .");
        List<ManifestEntry> entries = ManifestReader.read(ttl, "http://m.example/manifest.ttl");
        ManifestEntry e = entries.get(0);
        assertEquals("http://m.example/s.shex", e.schemaURL());
        assertEquals(List.of("<a> <b> <c> ."), e.dataTexts());
        assertEquals(List.of("http://m.example/a"), e.nodes());
        assertNull(e.shape());
        assertEquals("http://d.example/", e.dataBase());
        assertEquals(Boolean.TRUE, e.get("slurp"));
        assertEquals(List.of("http://m.example/ShExMapPlugin.js"), e.plugins());
        assertEquals("http://m.example/o.shex", e.string("outputSchemaURL"));
        assertEquals("v", ((Map<?, ?>)e.get("staticVars")).get("http://shex.io/extensions/Map/#k"));
    }
}
