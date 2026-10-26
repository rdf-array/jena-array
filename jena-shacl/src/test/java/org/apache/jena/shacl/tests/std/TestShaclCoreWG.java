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

package org.apache.jena.shacl.tests.std;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.jena.shacl.testing.ShaclTest;
import org.apache.jena.shacl.testing.ShaclTestItem;
import org.apache.jena.shacl.testing.ShaclTests;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class TestShaclCoreWG {

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() throws Exception {
        String manifest = "src/test/files/std/core/manifest.ttl";
        List<String> omitManifests = new ArrayList<>();
        return ShaclTests.junitParameters(manifest, omitManifests);
    }

    // rdf-array: a SHACL list is a sequence term; these tests depend on rdf:first/rdf:rest ladders.
    private static final Map<String, String> rdfArraySkips = Map.of(
        "path-strange-001", "rdf-array: the sh:path is an explicit rdf:first/rdf:rest ladder, which is triples, not a list",
        "path-strange-002", "rdf-array: the sh:path is an explicit rdf:first/rdf:rest ladder, which is triples, not a list",
        "shacl-shacl", "rdf-array: shacl-shacl's list shapes walk rdf:rest*/rdf:first over the shapes graph's lists, which are sequences");

    private final String name;
    private ShaclTestItem test;

    public TestShaclCoreWG(String name,  ShaclTestItem test) {
        this.name = name;
        this.test = test;
    }

    @Test
    public void test() {
        rdfArraySkips.forEach((id, reason) -> Assume.assumeFalse(reason, name.endsWith("("+id+")")));
        ShaclTest.shaclTest(test);
    }
}
