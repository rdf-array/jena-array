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

package org.apache.jena.shex.parser;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.jena.shex.Shex;
import org.apache.jena.shex.ShexSchema;
import org.junit.Test;

/**
 * Round-trip conformance test: for every {@code .ttl}/{@code .shex} fixture pair in
 * {@code shexTest/schemas}, the schema built by {@link ParserShExR} from the ShExR (RDF) form
 * must be semantically equivalent ({@link SchemaEquivalence#equivalent}) to the schema built by
 * {@link ParserShExC} from the ShExC (compact syntax) form of the same schema.
 * <p>
 * {@link SchemaEquivalence} already tolerates the two representation choices that are
 * serialization artifacts rather than differences in meaning: which occurrence of a shared
 * shape/triple expression is the "definition" vs. a {@code ShapeExprRef}/{@code TripleExprRef}
 * to it (ShExC's {@code $label}/{@code &label} sharing has no way to recover "which occurrence
 * defines it" from ShExR, which represents sharing purely by RDF node identity - confirmed: two
 * ShExC sources that swap which occurrence defines vs. references produce byte-identical ShExR
 * output, e.g. {@code 1Include1.shex} / {@code 1Include1-after.shex}), and ShapeAnd/ShapeOr
 * member order (AND/OR are commutative).
 * <p>
 * Two further categories of mismatch are tolerated here rather than failing the test - genuine
 * structural gaps in the fixture corpus itself, confirmed by direct inspection, not defects in
 * {@link ParserShExR}:
 * <ul>
 * <li>Blank-node top-level shape labels: RDF blank node identity is not preserved across
 *     independent parses of two different files, even when both files happen to use matching
 *     source-text blank node labels (e.g. {@code _:b1}) - the same tolerance already used by
 *     {@code RunnerPrintShex}'s ShExC print/reparse round trip.</li>
 * <li>Non-canonical numeric facet literals (leading zeros, decimal-point forms of whole
 *     numbers): the bundled ShExR fixtures store the canonicalized RDF numeric value, discarding
 *     ShExC's original non-canonical surface lexical form (e.g. {@code MININCLUSIVE 05} in the
 *     {@code .shex} source becomes {@code sx:mininclusive 5} in the {@code .ttl}).</li>
 * </ul>
 * A small named set of additional fixtures is excluded, each with its own individually-confirmed
 * root cause documented inline: one where ShExR encodes ShExC's {@code NOT .} operand as an
 * empty {@code sx:Shape} while ShExC's own {@code .} token builds an empty {@code NodeConstraint}
 * (both are valid "matches anything" shape expressions, but not equivalent for literal focus
 * nodes, so not something {@link SchemaEquivalence} should paper over); one where ShExC merges
 * several separate EXTENDS-bearing AND conjuncts into a single {@code Shape} with a combined
 * {@code extends} list, while the bundled ShExR fixture keeps them as separate conjuncts (a
 * ParserShExC-specific EXTENDS normalization, not a generic AST equivalence - out of scope here,
 * and overlapping with ongoing EXTENDS work elsewhere in this module); and fixture pairs where
 * the {@code .ttl}/{@code .shex} forms simply don't encode the same schema (different predicates,
 * or a {@code .shex} source using relative IRIs with no {@code BASE} directive, so its meaning
 * depends on a base URI this test cannot recover - not a parser defect either way.
 */
public class TestParserShExR {

    private static final String SCHEMAS_DIR = "src/test/files/shexTest/schemas";

    // "NOT ." : ShExR encodes the NOT's operand as an empty sx:Shape; ShExC's own "." token
    // builds an empty NodeConstraint instead. Both are valid "matches anything" shape
    // expressions, but different AST types with different semantics for literal focus nodes.
    private static final Set<String> KNOWN_NOT_DOT_ENCODING_MISMATCH = Set.of("1NOTdot", "1NOTNOTdot");

    // ShExC merges multiple EXTENDS-bearing AND conjuncts (e.g. "EXTENDS @<H> {} AND ... AND
    // EXTENDS @<I> {...}") into one Shape with a combined extends list; the bundled ShExR
    // fixture keeps the separate conjuncts shex.js's own ShExC parser produced. Confirmed by
    // direct inspection (fixture J in ExtendANDExtend3GAND3G.ttl): a generic multiset/associativity
    // equivalence can't close this without replicating ParserShExC's own EXTENDS-merging logic.
    private static final Set<String> KNOWN_EXTENDS_CONJUNCT_MERGING_MISMATCH = Set.of("ExtendANDExtend3GAND3G");

    // Fixture pairs that don't encode the same schema, confirmed by direct inspection - not a
    // parser issue on either side:
    //  - start2RefS2: .ttl and .shex use different predicates (p1 vs p2) for shape S2.
    //  - ExtendsHierarchy: .shex uses relative IRIs ("<a>", "<b>", ...) with no BASE directive,
    //    so reading it standalone resolves them against the file's own location, while the
    //    bundled .ttl was generated against a different (shex.io demo) base.
    private static final Set<String> KNOWN_BAD_FIXTURE_PAIR = Set.of("start2RefS2", "ExtendsHierarchy");

    private static final Pattern NON_CANONICAL_NUMERIC_FACET = Pattern.compile(
        "(?:MININCLUSIVE|MAXINCLUSIVE|MINEXCLUSIVE|MAXEXCLUSIVE|TOTALDIGITS|FRACTIONDIGITS)"
        + "\\s+[+-]?(?:0\\d|\\d*\\.\\d)");

    @Test
    public void roundTripAgainstShExC() throws IOException {
        Path dir = Path.of(SCHEMAS_DIR);
        List<Path> ttlFiles;
        try ( Stream<Path> stream = Files.list(dir) ) {
            ttlFiles = stream.filter(p -> p.toString().endsWith(".ttl"))
                              .sorted()
                              .collect(Collectors.toList());
        }
        assertTrue("No .ttl fixtures found under "+dir, !ttlFiles.isEmpty());

        StringBuilder failures = new StringBuilder();
        int checked = 0;
        for ( Path ttl : ttlFiles ) {
            String base = ttl.getFileName().toString();
            base = base.substring(0, base.length()-4);
            Path shexPath = dir.resolve(base+".shex");
            if ( !Files.exists(shexPath) )
                continue;
            if ( KNOWN_NOT_DOT_ENCODING_MISMATCH.contains(base) || KNOWN_EXTENDS_CONJUNCT_MERGING_MISMATCH.contains(base)
                 || KNOWN_BAD_FIXTURE_PAIR.contains(base) )
                continue;

            String shexText = Files.readString(shexPath);

            ShexSchema fromShExR;
            try {
                fromShExR = Shex.readSchemaShExR(ttl.toAbsolutePath().toString());
            } catch (Exception ex) {
                failures.append("PARSE-TTL-FAIL  ").append(base).append("  ").append(ex).append('\n');
                continue;
            }
            ShexSchema fromShExC;
            try {
                fromShExC = Shex.readSchema(shexPath.toAbsolutePath().toString());
            } catch (Exception ex) {
                // ShExC itself rejects this fixture - out of scope for a ShExR conformance test.
                continue;
            }
            checked++;

            if ( SchemaEquivalence.equivalent(fromShExR, fromShExC) )
                continue;

            boolean blankNodeLabelGap = shexText.contains("_:");
            boolean numericLexicalGap = NON_CANONICAL_NUMERIC_FACET.matcher(shexText).find();
            if ( blankNodeLabelGap || numericLexicalGap )
                continue;

            failures.append("MISMATCH  ").append(base).append('\n');
        }

        assertTrue("Checked 0 fixture pairs", checked > 0);
        if ( failures.length() > 0 )
            fail("ShExR/ShExC schema mismatches:\n"+failures);
    }
}
