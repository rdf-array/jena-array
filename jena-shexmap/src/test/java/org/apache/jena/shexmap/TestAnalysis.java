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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.junit.Test;

public class TestAnalysis {
    static final String PFX = "PREFIX : <http://e/>\nPREFIX v: <http://v/>\nPREFIX xsd: <http://www.w3.org/2001/XMLSchema#>\n"
            + "PREFIX Map: <http://shex.io/extensions/Map/#>\n";

    static final String INPUT = PFX + "start = @<Patient>\n"
            + "<Patient> { :name xsd:string %Map:{ v:name %} ;\n"
            + "  :reading { :sys xsd:integer %Map:{ v:sys %} ; :dia xsd:integer %Map:{ v:dia %} }* ;\n"
            + "  :visit { :when xsd:date %Map:{ v:when %} }* }\n";

    static ShexSchema schema(String text) { return Shex.schemaFromString(text, "http://e/"); }

    @Test public void aCoherentPairIsOk() {
        String output = PFX + "start = @<Chart>\n"
                + "<Chart> { :who xsd:string %Map:{ v:name %} ; :bp { :s xsd:integer %Map:{ v:sys %} ; :d xsd:integer %Map:{ v:dia %} }* ;"
                + " :seen { :on xsd:date %Map:{ v:when %} }* }\n";
        Analysis.Report r = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of());
        assertTrue(r.toString(), r.ok());
        assertTrue(r.warnings.isEmpty());
    }

    @Test public void readingAnIterationVariableAtTheRootAsksWhichOne() {
        String output = PFX + "start = @<Chart>\n<Chart> { :who xsd:string %Map:{ v:name %} ; :s xsd:integer %Map:{ v:sys %} }\n";
        Analysis.Report r = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of());
        assertFalse(r.ok());
        assertTrue(r.toString(), r.errors.stream().anyMatch(e -> e.contains("which one?")));
        assertTrue(r.toString(), r.warnings.stream().anyMatch(w -> w.contains("v:dia is bound")));
    }

    @Test public void aRepetitionOverUnrelatedListsIsIllFormed() {
        String output = PFX + "start = @<Chart>\n<Chart> { :x { :s xsd:integer %Map:{ v:sys %} ; :on xsd:date %Map:{ v:when %} }* }\n";
        Analysis.Report r = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of());
        assertFalse(r.ok());
        assertTrue(r.toString(), r.errors.stream().anyMatch(e -> e.contains("unrelated lists")));
    }

    @Test public void unboundReadsAndStatics() {
        String output = PFX + "start = @<Chart>\n<Chart> { :who xsd:string %Map:{ v:name %} ; :src IRI %Map:{ v:source %} }\n";
        Analysis.Report r = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of());
        assertTrue(r.toString(), r.errors.stream().anyMatch(e -> e.contains("never binds")));
        Analysis.Report ok = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of("http://v/source"));
        assertTrue(ok.toString(), ok.ok());
    }

    @Test public void idMisuseIsReported() {
        String output = PFX + "start = @<Chart>\n<Chart> { :who xsd:string %Map:{ id(v:name) %} ; :bp @<BP> %Map:{ id(@node) %} }\n<BP> { :s xsd:integer %Map:{ v:sys %} }\n";
        Analysis.Report r = ShExMap.analyse(schema(INPUT), schema(output), null, null, Set.of());
        assertTrue(r.toString(), r.errors.stream().anyMatch(e -> e.contains("needs a shape-valued constraint")));
        assertTrue(r.toString(), r.errors.stream().anyMatch(e -> e.contains("id(@node)")));
    }
}
