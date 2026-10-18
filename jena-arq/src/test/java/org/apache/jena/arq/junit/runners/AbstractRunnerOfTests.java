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

package org.apache.jena.arq.junit.runners;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.jena.arq.junit.manifest.*;
import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.junit.EarlReport;
import org.apache.jena.sparql.vocabulary.VocabTestQuery;
import org.junit.runner.Description;
import org.junit.runner.Runner;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.ParentRunner;
import org.junit.runners.model.InitializationError;

/**
 * Common super class for {@code @Runner(....)}.
 * <p>
 * Creates a runner for a manifest that has children for each included manifest and
 * each test defined in the manifest. It follow included manifests Annotations
 * supported:
 * <ul>
 * <li>{@code @Label("Some name")}</li>
 * <li>{@code @Manifests({"manifest1","manifest2",...})}</li>
 * </ul>
 * This class sorts out the annotations, including providing before/after class, then
 * creates a hierarchy of tests to run.
 *
 * @see RunnerOneTest
 */
public abstract class AbstractRunnerOfTests extends ParentRunner<Runner> {
    private Description  description;
    private List<Runner> children = new ArrayList<>();

    // Need unique names.

    public AbstractRunnerOfTests(Class<? > klass, Function<ManifestEntry, Runnable> maker) throws InitializationError {
        super(klass);
        String label = getLabel(klass);
        if ( label == null )
            label = klass.getName();
        String prefix = getPrefix(klass);
        String[] manifests = getManifests(klass);
        if ( manifests.length == 0 )
            //System.err.println("No manifests: "+label);
            throw new InitializationError("No manifests");
        description = Description.createSuiteDescription(label);

        for ( String manifestFile : manifests ) {
            //System.out.println("** "+klass.getSimpleName()+" -- "+manifestFile);
            if ( PrintManifests ) {
                out.println("** "+klass.getSimpleName()+" -- "+manifestFile);
                out.incIndent();
            }
            Manifest manifest = Manifest.parse(manifestFile);
            if ( PrintManifests ) {
                // Record manifests.
                Manifest.walk(manifest, m->out.println(m.getFileName()+" :: "+m.getName()), e->{});
            }
            Runner runner = build(null, manifest, maker, prefix);
            description.addChild(runner.getDescription());
            children.add(runner);
            if ( PrintManifests )
                out.decIndent();
        }
        if ( PrintManifests )
            out.flush();
    }

    // Print all manifests, top level and included.
    private static boolean PrintManifests = false;
    private static IndentedWriter out = IndentedWriter.stdout;

    /**
     * Do one level of tests. test are {@link Runnable Runnables} that each succeed or fail with an exception.
     */
    public static RunnerOneManifest build(EarlReport report, Manifest manifest, Function<ManifestEntry, Runnable> maker, String prefix) {
        Description description = Description.createSuiteDescription(manifest.getName());
        if ( PrintManifests )
            out.println(manifest.getFileName()+" :: "+manifest.getName());
        RunnerOneManifest thisLevel = new RunnerOneManifest(manifest, description);

        Iterator<String> sub = manifest.includedManifests();
        while(sub.hasNext() ) {
            if ( PrintManifests )
                out.incIndent();

            String mf = sub.next();
            Manifest manifestSub = Manifest.parse(mf);
            Runner runner = build(report, manifestSub, maker, prefix);
            thisLevel.add(runner);
            if ( PrintManifests )
                out.decIndent();
        }

        // Check entries do have test targets.

        manifest.entries().forEach(entry->{
            if ( entry.getAction() == null )
                throw new RuntimeException("Missing: action ["+entry.getEntry()+"]");
            if ( entry.getName() == null )
                throw new RuntimeException("Missing: label ["+entry.getEntry()+"]");
        });

        prepareTests(report, thisLevel, manifest, maker, prefix);
        return thisLevel;
    }

    private static String prepareTestLabel(ManifestEntry entry, String prefix) {
        String label = fixupName(entry.getName());
        if ( prefix != null )
            label = prefix+label;

        // action URI or action -> qt:query
        String str = null;

        if ( entry.getAction() != null ) {
            if ( entry.getAction().isURIResource() )
                str = entry.getAction().getURI();
            else if ( entry.getAction().isAnon() ) {
                Statement stmt = entry.getAction().getProperty(VocabTestQuery.query);
                if ( stmt != null && stmt.getObject().isURIResource() )
                    str = stmt.getObject().asResource().getURI();
            }
        }

        if ( str != null ) {
            int x = str.lastIndexOf('/');
            if ( x > 0 && x < str.length() ) {
                String fn = str.substring(x+1) ;
                label = label+" ("+fn+")";
            }
        }
        return label;
    }

    public static void prepareTests(EarlReport report, RunnerOneManifest level, Manifest manifest, Function<ManifestEntry, Runnable> maker, String prefix) {
        String testsEnv = System.getenv("TESTS");
        List<Pattern> selected = testsEnv == null
                ? new ArrayList<>(List.of(Pattern.compile(".*")))
                : Arrays.stream(testsEnv.split(";"))
                    .map(s -> Pattern.compile(s))
                    .collect(Collectors.toList());

        selected.forEach(pattern->{
            Stream<ManifestEntry> matched = manifest.entries().stream().filter(entry->{
                String label = prepareTestLabel(entry, prefix);
                if (pattern.matcher(label).matches()) {
                    Runnable runnable = maker.apply(entry);
                    if (runnable != null) {
                        Runner r = new RunnerOneTest(label, runnable, entry.getURI(), report);
                        level.add(r);
                    }
                    return true;
                }
                return false;
            });
            // A manifest that only includes other manifests has no entries of its own.
            if (matched.count() == 0 && ! manifest.entries().isEmpty()) {
                if (testsEnv == null) {
                    throw new RuntimeException("No manifest entries found");
                } else {
                    throw new RuntimeException("TESTS pattern " + pattern + " did not match any of " + manifest.entries().stream().map(l -> "\n  " + l.getName()).collect(Collectors.joining()));
                }
            }
        });
    }

    public static String fixupName(String string) {
        // Eclipse used to parse test names and () were special. 
//        string = string.replace('(', '[');
//        string = string.replace(')', ']');
        return string;
    }

    private static String getLabel(Class<? > klass) {
        Label annotation = klass.getAnnotation(Label.class);
        return ( annotation == null ) ? null : annotation.value();
    }

    private static String getPrefix(Class<? > klass) {
        Prefix annotation = klass.getAnnotation(Prefix.class);
        return ( annotation == null ) ? null : annotation.value();
    }

    private static String[] getManifests(Class<? > klass) throws InitializationError {
        String manifestEnv = System.getenv("MANIFESTS");
        String[] ret;
        if (manifestEnv == null) {
            Manifests annotation = klass.getAnnotation(Manifests.class);
            if ( annotation == null ) {
                throw new InitializationError(String.format("class '%s' must have a @Manifests annotation", klass.getName()));
            }
            ret = annotation.value();
        } else {
            ret = Arrays.stream(manifestEnv.split(";")).toArray(String[]::new);

            // Make sure manifest files exist (assumed unnecessary if found by annotation).
            for (String manifestEntry : ret) {
                Path absoluteManifestPath = Path.of(manifestEntry);
                String absolutePathStr = absoluteManifestPath.toAbsolutePath().toString();
                File override = new File(absolutePathStr);
                if (!override.exists())
                    throw new InitializationError("Can't find manifest file " + absolutePathStr);
            }
        }
        return ret;
    }

    @Override
    public Description getDescription() {
        return description;
    }

    @Override
    protected List<Runner> getChildren() {
        return children;
    }

    @Override
    protected Description describeChild(Runner child) {
        return child.getDescription();
    }

    @Override
    protected void runChild(Runner child, RunNotifier notifier) {
        child.run(notifier);
    }
}
