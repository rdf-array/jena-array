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

package org.apache.jena.external;

import org.apache.jena.arq.junit.manifest.Manifests;
import org.apache.jena.arq.junit.runners.Label;
import org.apache.jena.arq.junit.runners.RunnerSPARQL;
import org.junit.runner.RunWith;

/**
 * rdf-array: the SPARQL-array tests of rdf-tests-contrib
 * (https://github.com/rdf-array/rdf-tests-contrib), copied into testing/rdf-array by its
 * update.sh: sequence and position patterns, the sequence functions, update, syntax and the
 * result formats.
 */
@RunWith(RunnerSPARQL.class)
@Label("rdf-array rdf-tests-contrib - SPARQL")
@Manifests({
    "testing/rdf-array/rdf-tests-contrib/sparql/eval/manifest.ttl"
    ,"testing/rdf-array/rdf-tests-contrib/sparql/update/manifest.ttl"
    ,"testing/rdf-array/rdf-tests-contrib/sparql/syntax-query/manifest.ttl"
    ,"testing/rdf-array/rdf-tests-contrib/sparql/results/manifest.ttl"
})
public class Scripts_RdfArrayContribSPARQL {}
