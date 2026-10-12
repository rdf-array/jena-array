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

package shex;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.jena.atlas.logging.LogCtl;
import org.apache.jena.cmd.ArgDecl;
import org.apache.jena.cmd.CmdException;
import org.apache.jena.cmd.CmdGeneral;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shex.*;
import org.apache.jena.shex.manifest.ManifestRunner;
import org.apache.jena.shex.manifest.ManifestRunner.EntryRun;
import org.apache.jena.shex.reporting.ShexValidationReport;
import org.apache.jena.shex.sys.ShexLib;
import org.apache.jena.sys.JenaSystem;

/** ShEx validation.
 * <p>
 * Usage: <code>shex validate [--text] --shapes SHAPES --data DATA</code>
 */
public class shex_validate extends CmdGeneral {

    static {
        LogCtl.setLogging();
        JenaSystem.init();
    }

    private ArgDecl argOutputText  = new ArgDecl(false, "--text");
    //private ArgDecl argOutputRDF   = new ArgDecl(false, "--rdf");
    private ArgDecl argData        = new ArgDecl(true, "--data", "--datafile", "-d");
    private ArgDecl argShapes      = new ArgDecl(true, "--schema", "--shapes", "--shapesfile", "--shapefile", "-s");
    private ArgDecl argShapeMap    = new ArgDecl(true, "--shapesMap", "--shapesmap","--map", "-m");
    private ArgDecl argTargetNode  = new ArgDecl(true, "--target", "--node", "-n");
    private ArgDecl argManifest    = new ArgDecl(true, "--manifest");
    private ArgDecl argEntry       = new ArgDecl(true, "--entry", "-e");

    private String  datafile = null;
    private String  shapesfile = null;
    private String  mapfile = null;
    private String  targetNode = null;  // Parse later.
    private String  manifestfile = null;
    private List<Pattern> entryPatterns = new ArrayList<>();
    private boolean textOutput = false;

    public static void main (String... argv) {
        new shex_validate(argv).mainRun() ;
    }

    public shex_validate(String[] argv) {
        super(argv) ;
        super.add(argShapes,        "--schema", "Shapes file");
        super.add(argData,          "--data",   "Data file");
        super.add(argShapeMap,      "--map",    "ShEx shapes map");
        super.add(argTargetNode,    "--target", "Validate specific node [may use prefixes from the data]");
        super.add(argOutputText,    "--text",   "Output in concise text format");
        super.add(argManifest,      "--manifest", "Run the entries of a ShEx examples manifest (YAML-LD, JSON or Turtle; "
                                                  + "file or URL), each with the schema, data and shape map it names -- the "
                                                  + "document may itself be the schema, as ShExR, and the data");
        super.add(argEntry,         "--entry",  "With --manifest: only the entries whose name, or schemaLabel / dataLabel, "
                                                + "this regular expression matches (repeatable)");
        //super.add(argOutputRDF,  "--rdf", "Output in RDF (Turtle) format");
    }

    @Override
    protected String getSummary() {
        return getCommandName()+" [--target URI|--map mapsFile] --shapes shapesFile --data dataFile | --manifest manifestFile [--entry REGEX]";
    }

    @Override
    protected void processModulesAndArgs() {
         super.processModulesAndArgs();
         textOutput = super.hasArg(argOutputText);

         if ( contains(argManifest) ) {
             manifestfile = getValue(argManifest);
             for ( String pattern : getValues(argEntry) ) {
                 try {
                     entryPatterns.add(Pattern.compile(pattern));
                 } catch (PatternSyntaxException ex) {
                     throw new CmdException("Bad --entry pattern '" + pattern + "': " + ex.getDescription());
                 }
             }
             if ( contains(argData) || contains(argShapes) || contains(argShapeMap) || contains(argTargetNode) )
                 throw new CmdException("--manifest takes the data, schema and shape map from the manifest's entries");
             return;
         }

         datafile = super.getValue(argData);
         shapesfile = super.getValue(argShapes);

         // No -- arguments, use act on single file of shapes and data.
         if ( datafile == null && shapesfile == null ) {
             if ( positionals.size() == 1 ) {
                 datafile = positionals.get(0);
                 shapesfile = positionals.get(0);
             }
         }

         if ( datafile == null )
             throw new CmdException("Usage: "+getSummary());
         if ( shapesfile == null )
             shapesfile = datafile;

         if ( contains(argTargetNode) )
             targetNode = getValue(argTargetNode);

         if ( contains(argShapeMap) )
             mapfile = getValue(argShapeMap);

         if ( targetNode != null && mapfile != null )
             throw new CmdException("Only one of target node and map file");

         if ( targetNode == null && mapfile == null )
             throw new CmdException("One of target node and map file required");
    }

    static OutputStream output = System.out;

    @Override
    protected void exec() {
        if ( manifestfile != null ) {
            execManifest();
            return;
        }

        ShexSchema shapes;
        try {
            shapes = Shex.readSchema(shapesfile);
        } catch (ShexException ex) {
            throw new CmdException("Failed to read shapes: " + ex.getMessage());
        }

        Graph dataGraph;
        try {
            dataGraph = load(datafile, "data file");
        } catch (RiotException ex) {
            throw new CmdException("Failed to load data: " + ex.getMessage());
        }

//        if ( targetNode != null ) {
//            String x = dataGraph.getPrefixMapping().expandPrefix(targetNode);
//            Node node = NodeFactory.createURI(x);
//            ShexValidation.validate(graphData, shapes, shapeRef, focus)
//        }

        if ( mapfile != null ) {
            ShapeMap map;
            try {
                map = Shex.readShapeMap(mapfile);
            } catch (ShexException ex) {
                throw new CmdException("Failed to read shapes map: " + ex.getMessage());
            }
            ShexValidationReport report = ShexValidator.getNew(List.of()).validate(dataGraph, shapes, map);
            ShexLib.printReport(output, report);
            return;
        }

        // Or --shapeURI
        ShapeDecl startShape = shapes.getStart();
        if ( startShape == null )
            throw new CmdException("Start node required for URI-validation");

        String targetURIstr = dataGraph.getPrefixMapping().expandPrefix(targetNode);
        Node focus = NodeFactory.createURI(targetURIstr);

//        if ( isVerbose() )
//            ValidationContext.VERBOSE = true;

//        ValidationReport report = ( node != null )
//            ? ShaclValidator.get().validate(shapesGraph, dataGraph, node)
//            : ShaclValidator.get().validate(shapesGraph, dataGraph);

        ShexValidationReport report = ShexValidator.getNew(List.of()).validate(dataGraph, shapes, startShape, focus);
        ShexLib.printReport(output, report);
    }

    /**
     * {@code --manifest}: every entry validated as it says, one line apiece -- PASS when its
     * verdict is the one its status expects (or, without a status, conformant), else FAIL
     * followed by its report -- and a failure when any entry failed.
     */
    private void execManifest() {
        List<EntryRun> runs;
        try {
            runs = ManifestRunner.run(manifestfile, entryPatterns);
        } catch (ShexException ex) {
            throw new CmdException("Failed to run manifest: " + ex.getMessage());
        }
        PrintStream out = new PrintStream(output, true);
        int failures = 0;
        for ( EntryRun run : runs ) {
            if ( run.passed() ) {
                out.println("PASS " + run.entry.label() + ": " + run.verdict());
                continue;
            }
            failures++;
            String expected = run.entry.status() == null ? "" : ", expected " + run.entry.status();
            out.println("FAIL " + run.entry.label() + ": " + run.verdict() + expected);
            if ( run.error != null )
                out.println("  Error: " + run.error);
            else
                ShexLib.printReport(output, run.report);
        }
        out.flush();
        if ( failures > 0 )
            throw new CmdException(failures + " of " + runs.size() + " manifest "
                                   + (runs.size() == 1 ? "entry" : "entries") + " did not validate as its status says");
    }

    private Graph load(String filename, String scope) {
        try {
            Graph graph = RDFDataMgr.loadGraph(filename);
            return graph;
        } catch (RiotException ex) {
            System.err.println("Loading "+scope);
            throw ex;
        }
    }

    @Override
    protected String getCommandName() {
        return "shex_validate";
    }
}
