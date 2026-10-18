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

package org.apache.jena.shex.jsonld;

public enum JsonGEnum {
    TYPE("type"),
    LANGUAGE("language"),
    LANGUAGE_TAG("languageTag"),
    EXCLUSIONS("exclusions"),
    STEM("stem"),
    WILDCARD("Wildcard"),
    IMPORT("import"),
    ABSTRACT("abstract"),
    SCHEMA("Schema"),
    START("start"),
    SHAPES("shapes"),
    ID("id"),
    SHAPE_EXPRESSION("shapeExpr"),
    SHAPE_EXPRESSIONS("shapeExprs"),
    RESTRICTS("restricts"),

    SHAPE_DECL("ShapeDecl"),
    SHAPE("Shape"),
    SHAPE_OR("ShapeOr"),
    SHAPE_AND("ShapeAnd"),
    SHAPE_NOT("ShapeNot"),
    SHAPE_EXTERNAL("ShapeExternal"),
    COLLECTION("Collection"),            // RDF sequences: ((shapeExpr))[min, max]
    EXPRESSION("expression"),
    EXPRESSIONS("expressions"),
    TRIPLE_CONSTRAINT("TripleConstraint"),
    PREDICATE("predicate"),
    VALUE_EXPRESSION("valueExpr"),
    NODE_CONSTRAINT("NodeConstraint"),
    VALUES("values"),
    VALUE("value"),
    EACH_OF("EachOf"),
    ONE_OF("OneOf"),
    CARDINALITY_MIN("min"),
    CARDINALITY_MAX("max"),
    DATATYPE("datatype"),
    SEM_ACTS("semActs"),
    START_ACTS("startActs"),
    NAME("name"),
    CODE("code");

    private String label;

    private JsonGEnum(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
