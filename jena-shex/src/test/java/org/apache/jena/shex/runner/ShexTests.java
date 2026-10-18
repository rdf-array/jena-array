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

package org.apache.jena.shex.runner;

import org.apache.jena.arq.junit.manifest.Manifest;
import org.apache.jena.arq.junit.manifest.ManifestEntry;
import org.apache.jena.arq.junit.manifest.Prefix;
import org.apache.jena.arq.junit.runners.Label;
import org.apache.jena.atlas.lib.FileOps;
import org.apache.jena.atlas.lib.Pair;
import org.apache.jena.atlas.logging.Log;
import org.apache.jena.atlas.web.TypedInputStream;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.system.stream.Locator;
import org.apache.jena.riot.system.stream.LocatorFile;
import org.apache.jena.riot.system.stream.StreamManager;
import org.apache.jena.shex.expressions.Sx2;
import org.apache.jena.vocabulary.RDF;

import java.util.*;
import java.util.stream.Collectors;

public class ShexTests {
    static boolean VERBOSE = false;

    // Validation test filtering.
    static Set<Resource> excludeTraits = new HashSet<>();
    static Set<String> excludes = new HashSet<>();
    static Set<String> includes = new LinkedHashSet<>();
    static boolean dumpTest = false;
    static StreamManager streamMgr = StreamManager.get().clone();


    static {
        setup();
        //Sx.TRACE : true if there are inclusions
        //includes.add("#1val1IRIREFExtra1_pass-iri2");
        //includes.add("#start2RefS1-IstartS2");
        //includes.add("#1dot_pass-noOthers");

        VERBOSE = ! includes.isEmpty();

        // --- Exclusions - development

        // BNodes tests we don't support (testing labels, cross file references)
        // excludeTraits.add(ShexT.tExtends);
        excludeTraits.add(ShexT.tCrossFileBNodeShapeLabel);
        excludeTraits.add(ShexT.tRefBNodeShapeLabel);
        excludeTraits.add(ShexT.tLexicalBNode);
        excludeTraits.add(ShexT.tToldBNode);
        // need to invoke a 2nd parser to external Shapes and SemActs
        excludeTraits.add(ShexT.tExternalShape);
        excludeTraits.add(ShexT.tExternalSemanticAction);

        // Syntax exclusions - may be needed here.
        //    //---- Exclusions
        //    // java does to support character classes \N (all numbers)
        //    excludes.add("1literalPattern_with_all_meta.shex");
        //
        //    // Unclear. Breaks when fix for unicode escapes is applied.
        //    // Is this because of the incompatible REGEX language choice?
        //    excludes.add("1literalPattern_with_REGEXP_escapes_escaped.shex");
        //
        //    // Regex has a null (unicode codepoint 0000) which is illegal.
        //    excludes.add("1literalPattern_with_ascii_boundaries.shex");
        //
        //    // Contains \ud800 (ill-formed surrogate pair)
        //    excludes.add("1refbnode_with_spanning_PN_CHARS_BASE1.shex");
        //
        //    // Contains \u0d00 (ill-formed surrogate pair)
        //    excludes.add("_all.shex");
        //---- Exclusions

        if ( ShexTests.VERBOSE ) {
            System.err.println("Validation");
            System.err.println("  inclusions    = "+includes.size());
            System.err.println("  exclusions    = "+excludes.size());
            System.err.println();
            dumpTest = ! includes.isEmpty();
        }

        if ( ! includes.isEmpty() )
            Sx2.TRACE = true;
    }

    /** Create a Shex test - or return null for "unrecognized" */
    public static Runnable makeShexValidationTest(ManifestEntry entry) {
        if ( ! runTestExclusionsInclusions(entry) )
            return null;

        Resource testType = entry.getTestType();
        if ( testType == null ) {
            //RepresentationTest
            System.out.println("No test type: " + entry.getName());
            return null;
        }

        if ( testType.equals(ShexT.cRepresentationTest) ) {
            return ()->{};
        }

        Resource action = entry.getAction();
        if ( action == null ) {
            System.out.println("Action expected: " + entry.getName());
            return null;
        }

        if ( action.hasProperty(ShexT.semActs) ) {}
        if ( action.hasProperty(ShexT.shapeExterns) ) {}


        if ( testType == null ) { }

        //action.getProperty(ShexT.trait);

        if ( ! testType.equals(ShexT.cValidationTest) && ! testType.equals(ShexT.cValidationFailure) ) {
            System.err.println("Skip unknown test type for: "+entry.getName());
            return ()->{};
        }

        // -- Check
        // map or (shape+focus)
        if ( ! action.hasProperty(ShexT.schema) ) {
            System.err.println("Bad: no schema : "+entry.getName());
            return null;
        }

        if ( action.hasProperty(ShexT.map) ) {
            if ( action.hasProperty(ShexT.shape) || action.hasProperty(ShexT.focus) ) {
                System.err.println("Bad: map + (shape or focus) : "+entry.getName());
            }
            if ( !action.hasProperty(ShexT.data) || ! action.hasProperty(ShexT.schema)) {
                System.err.println("Bad: map + no (data+schema) : "+entry.getName());
            }
        } else {
            // Not map
            if ( ! action.hasProperty(ShexT.schema) || ! action.hasProperty(ShexT.data) || ! action.hasProperty(ShexT.focus) )
                System.err.println("Bad: no map, no (scheme/data/focus) : "+entry.getName());
        }
        // -- Check

        if ( testType.equals(ShexT.cValidationTest) || testType.equals(ShexT.cValidationFailure) ) {
            boolean faiureTest = testType.equals(ShexT.cValidationFailure);

            if ( action.hasProperty(ShexT.shape) ) {
                // Expected: ShexT.schema, ShexT.shape, ShexT.data, ShexT.focus
                try {
                    Resource schema = action.getProperty(ShexT.schema).getResource();
                    Resource shape = action.getProperty(ShexT.shape).getResource();
                    Resource data = action.getProperty(ShexT.data).getResource();
                    // URI or literal.
                    RDFNode focus = action.getProperty(ShexT.focus).getObject();
                    return ShexValidationTest.testShexValidationShapeFocus(entry);
                } catch (Exception ex) {
                    System.err.println(ex.getClass().getName());
                    System.err.println(ex.getMessage());
                    System.err.println(entry.getEntry().getLocalName());
                    return null;
                }
            }

            // Roll into the above?
            if ( action.hasProperty(ShexT.focus) ) {
                // Expected: ShexT.schema (with start), ShexT.data, ShexT.focus
                try {
                    Resource schema = action.getProperty(ShexT.schema).getResource();
                    // No shape.
                    Resource data = action.getProperty(ShexT.data).getResource();
                    // URI or literal.
                    RDFNode focus = action.getProperty(ShexT.focus).getObject();
                    return ShexValidationTest.testShexValidationStartFocus(entry);
                } catch (Exception ex) {
                }
            }

            if ( action.hasProperty(ShexT.map) ) {
                // Expected: ShexT.schema (with start), ShexT.map, ShexT.data
                try {
                    Resource schema = action.getProperty(ShexT.schema).getResource();
                    Resource map = action.getProperty(ShexT.map).getResource();
                    Resource data = action.getProperty(ShexT.data).getResource();
                    return ShexValidationTest.testShexValidationMap(entry);
                } catch (Exception ex) {
                    System.err.println(ex.getClass().getName());
                    System.err.println(ex.getMessage());
                    System.err.println(entry.getEntry().getLocalName());
                    return null;
                }
            }

            // Unknown.
            System.err.println("Unknown: "+entry.getName());
            return null;

        } else {
            Log.warn("ShexTests", "Skip unknown test type for: "+entry.getName());
            return null;
        }
    }

    public static List<Resource> extractTraits(ManifestEntry entry) {
        Resource traitsRsrc = Manifest.getResource(entry.getEntry(), ShexT.trait) ;
        if (traitsRsrc == null)
            return null;
        List<Statement> x = entry.getEntry().listProperties(ShexT.trait).toList();
        return x.stream().map(t -> t.getObject().asResource()).collect(Collectors.toList());
    }

    private static boolean runTestExclusionsInclusions(ManifestEntry entry) {
        String fragment = fragment(entry);

        if ( fragment != null ) {
            // Includes, if present.
            if ( includes.contains(fragment) )
                return true;
            if ( ! includes.isEmpty() )
                return false;

            if ( excludes.contains(fragment) ) {
                // [shex] Convert to "ignored"
                //System.err.println("Skipping:  "+fragment);
                return false;
            }

            List<Resource> traits = extractTraits(entry);
            if (traits != null) {
                List<Resource> excludedBecause = traits.stream().filter(excl -> excludeTraits.contains(excl)).collect(Collectors.toList());
                if (excludedBecause.size() > 0)
                    return false;
            }
        }
        return true;
    }

    public static List<Pair<Resource, String>> extractExtensionResults(ManifestEntry entry) {
        Resource extensionResults = Manifest.getResource(entry.getEntry(), ShexT.extensionResults) ;
        if (extensionResults == null)
            return null;
        List<Pair<Resource, String>> pairs = new ArrayList<>();
        StmtIterator listIter = entry.getEntry().listProperties(ShexT.extensionResults);
        while (listIter.hasNext()) {
            //List head
            Resource listHead = listIter.nextStatement().getResource();
            for ( Resource extensionResult : listMembers(listHead) ) {
                Resource extension = extensionResult.getProperty(ShexT.extension).getResource();
                Literal prints = extensionResult.getProperty(ShexT.prints).getLiteral();
                String printStr = prints.getString() ;
                Pair<Resource, String> pair = new Pair<>(extension, printStr);
                pairs.add(pair);
            }
        }
        listIter.close();
        return pairs;
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

    // [shex] Migrate
    public static String fragment(ManifestEntry entry) {
        if ( entry.getEntry().isURIResource() )
            return fragment(entry.getEntry().getURI());
        return null;
    }

    // [shex] Migrate
    public static String fragment(String uri) {
        int j = uri.lastIndexOf('#') ;
        String fn = (j >= 0) ? uri.substring(j) : uri ;
        return fn ;
    }

    /*package*/ static String getLabel(Class<? > klass) {
        Label annotation = klass.getAnnotation(Label.class);
        return ( annotation == null ) ? null : annotation.value();
    }

    /*package*/ static String getPrefix(Class<? > klass) {
        Prefix annotation = klass.getAnnotation(Prefix.class);
        return ( annotation == null ) ? null : annotation.value();
    }

    private static void setup() {
        // Setup StreamManager.
        String places[] = { "src/test/files/shexTest/schemas/", "src/test/files/spec/validation/" };


        for ( String dir : places ) {
            Locator alt = new LocatorShexTest(dir);
            streamMgr.addLocator(alt);
        }
        StreamManager.setGlobal(streamMgr);
    }

    // Hunt files.
    static class LocatorShexTest extends LocatorFile {
        public LocatorShexTest(String dir) {
            super(dir);
        }

        @Override
        public TypedInputStream open(String filenameOrURL) {
            String fn = FileOps.basename(filenameOrURL);
            String url = fn.endsWith(".shex")
                    ? fn
                    : fn+".shex";
            return super.open(url);
        }

        @Override
        public String getName() {
            return "LocatorShexTest";
        }
    }
}
