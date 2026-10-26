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

import org.apache.jena.shacl.testing.ShaclTest;
import org.apache.jena.shacl.testing.ShaclTestItem;
import org.apache.jena.shacl.testing.ShaclTests;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/** rdf-array: SHACL 1.2's list-constraint tests as data-shapes-array re-records or rejects them (copied by std/update.sh); jena-array implements sh:memberShape, sh:minListLength, sh:maxListLength and sh:uniqueMembers over sequences. */
@RunWith(Parameterized.class)
public class TestShacl12Lists {

    @Parameters(name = "{0}")
    public static Collection<Object[]> data() throws Exception {
        String manifest = "src/test/files/std12-lists/manifest.ttl";
        List<String> omitManifests = new ArrayList<>();
        return ShaclTests.junitParameters(manifest, omitManifests);
    }

    private ShaclTestItem test;

    public TestShacl12Lists(String name,  ShaclTestItem test) {
        this.test = test;
    }

    @Test
    public void test() { ShaclTest.shaclTest(test); }
}
