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

package org.apache.jena.shacl.testing;

import static java.util.stream.Collectors.toList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.jena.atlas.lib.FileOps;
import org.apache.jena.atlas.lib.InternalErrorException;
import org.apache.jena.atlas.logging.Log;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.shacl.test_vocab.MF;
import org.apache.jena.shacl.test_vocab.SHT;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

public class ShaclTests {

    public static List<ShaclTestItem> manifest(String manifestFile) {
        return manifest(manifestFile, Collections.emptyList());
    }

    public static List<ShaclTestItem> manifest(String manifestFile, List<String> omitManifests) {
        List<String> manifests = new ArrayList<>();
        manifests.addAll(omitManifests);
        List<ShaclTestItem> testCases = new ArrayList<>();
        manifest(manifestFile, manifests, testCases);
        return testCases;
    }

    public static List<Object[]> junitParameters(String manifestFile, List<String> omitManifests) {
        List<ShaclTestItem> testCases = manifest(manifestFile, omitManifests);
        return testCases.stream().map(stc->new Object[] {decideName(stc), stc}).collect(toList());
    }

    private static String decideName(ShaclTestItem stc) {
        String fn = FileOps.basename(stc.origin());
        return stc.name()+"("+fn+")";
    }

    private static void manifest(String manifestFile, List<String> manifests, List<ShaclTestItem> testCases) {
        if ( manifests.contains(manifestFile) )
            return;
        manifests.add(manifestFile);
        // System.err.println("Load: "+manifestFile);
        Model model = RDFDataMgr.loadModel(manifestFile);
        List<String> includedFiles = new ArrayList<>();

        StmtIterator manifestStmts = model.listStatements(null, RDF.type, MF.Manifest);
        for ( ; manifestStmts.hasNext() ; ) {
            Statement stmt = manifestStmts.next();
            Resource manifest = stmt.getSubject();
            parseIncludes(manifest, manifests, testCases);
            processEntries(manifestFile, manifest, testCases);
        }
    }

    private static void parseIncludes(Resource manifest, List<String> manifests, List<ShaclTestItem> testCases) {
        StmtIterator includeStmts = manifest.listProperties(MF.include);
        for ( ; includeStmts.hasNext() ; ) {
            Statement s = includeStmts.nextStatement();
            if ( !(s.getObject() instanceof Resource) ) {
                Log.warn(ShaclTestItem.class, "Include: not a Resource" + s);
                continue;
            }
            Resource r = s.getResource();
            parseOneIncludesList(r, manifests, testCases);
        }
        includeStmts.close();
    }

    private static void parseOneIncludesList(Resource r, List<String> manifests, List<ShaclTestItem> testCases) {
        if ( r == null )
            return;

        if ( r.equals(RDF.nil) )
            return;

        if ( !r.isAnon() && !r.asNode().isNodeSeq() ) {
            String uri = r.getURI();
            manifest(uri, manifests, testCases);
            return;
        }

        // BNnode => list
        for ( Resource member : listMembers(r) )
            manifest(member.getURI(), manifests, testCases);
    }

    private static void processEntries(String file, Resource manifest, List<ShaclTestItem> testCases) {
        StmtIterator entriesStmts = manifest.listProperties(MF.entries);
        for ( ; entriesStmts.hasNext() ; ) {
            Statement s = entriesStmts.nextStatement();
            processEntry(file, s.getResource(), testCases);
        }
    }

    // For every test item
    private static void processEntry(String file, Resource r, List<ShaclTestItem> testCases) {
        if ( r == null )
            return;

        if ( r.equals(RDF.nil) )
            return;

        if ( !r.isAnon() && !r.asNode().isNodeSeq() ) {
            ShaclTestItem tc = entry(file, r);
            testCases.add(tc);
            return;
        }

        // BNnode => list
        for ( Resource member : listMembers(r) ) {
            ShaclTestItem tc = entry(file, member);
            testCases.add(tc);
        }
    }

    // RDF sequences: a list is either a sequence term or a legacy rdf:first/rdf:rest ladder.
    private static java.util.List<Resource> listMembers(Resource listHead) {
        java.util.List<Resource> members = new java.util.ArrayList<>();
        if ( listHead.asNode().isNodeSeq() ) {
            for ( org.apache.jena.graph.Node elt : listHead.asNode().getSeq() )
                members.add(listHead.getModel().asRDFNode(elt).asResource());
            return members;
        }
        Resource listItem = listHead;
        while ( !listItem.equals(RDF.nil) ) {
            members.add(listItem.getRequiredProperty(RDF.first).getResource());
            listItem = listItem.getRequiredProperty(RDF.rest).getResource();
        }
        return members;
    }

    private static ShaclTestItem entry(String file, Resource entry) {
// System.out.println("T:"+entry.listProperties(RDF.type).mapWith(Statement::getObject).toList());
// System.out.println("E:"+entry.listProperties(MF.action).mapWith(Statement::getObject).toList());
// System.out.println("R:"+entry.listProperties(MF.result).mapWith(Statement::getObject).toList());

        Resource type = entry.getRequiredProperty(RDF.type).getResource();
        String sName = type.isResource() ? entry.getLocalName() : "[]";
        String name = entry.hasProperty(RDFS.label) ? entry.getProperty(RDFS.label).getString() : sName;

        if ( type.equals(SHT.Validate) ) {
            return new ShaclTestItem(name, file, entry);
        }
        throw new InternalErrorException("Unexpected test type : " + type);
    }
}
