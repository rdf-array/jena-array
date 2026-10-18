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

import com.github.jsonldjava.utils.JsonUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.ShexSchema;
import org.apache.jena.shex.expressions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ShexJsonldDeserializer {

    /**
     * Model used for node retrieval or creation. If no model is passed in, a new one is created for the purpose.
     */
    private Model model = null;

    /**
     * Index used to flesh shapes referenced in Json-ld payload.
     */
    private Map<String, ShapeDecl> shapeDeclIndex = new HashMap<>();

    /**
     * No-arg constructor to create new ShEx Json-ld deserializer.
     * Creates a new model for node creation.
     */
    public ShexJsonldDeserializer() {
        model = ModelFactory.createDefaultModel();
    }

    /**
     * Creates a new ShEx Json-ld deserializer using passed in model
     * to resolve or create nodes.
     *
     * @param model
     */
    public ShexJsonldDeserializer(Model model) {
        this.model = model;
    }

    /**
     * Returns reference to model used by the deserializer.
     *
     * @return
     */
    public Model getModel() {
        return model;
    }

    /**
     * Entry point for JSON-LD deserialization.
     *
     * @param is The input stream for the JSON-LD object to deserialize.
     * @return
     */
    public ShexSchema deserializeSchema(InputStream is) { //TODO Currently only entry point. Are there others?
        return (ShexSchema) deserializeObject(is, JsonGEnum.SCHEMA);
    }

    /**
     * The ShexJsonLdDeserializer maintains state. If it is reused, be sure to
     * clear its state.
     *
     */
    public void clearState(Model model) {
        this.model = model;
        shapeDeclIndex.clear();
    }

    /**
     * Method used to deserialize an arbitrary JSON object.
     * At this time, only ShexSchema objects are supported.
     *
     * @param is
     * @param item
     * @return
     */
    protected Object deserializeObject(InputStream is, JsonGEnum item) {
        Object jenaShexObject = null;
        try {
            Map deserializedJsonObject = (Map)JsonUtils.fromInputStream(is);
            String type = ShexJsonldDeserializationUtils.getTypeJsonField(deserializedJsonObject);
            if(type.equalsIgnoreCase(JsonGEnum.SCHEMA.getLabel())) {
                jenaShexObject = handleSchema(deserializedJsonObject);
            } else {
                throw new RuntimeException("Unsupported or unrecognized type " + type + ". Support for this type may be added at a later time");
            }
        } catch(Exception e) {
            throw new RuntimeException("Error parsing jsonld", e);
        }
        return jenaShexObject;
    }

    /**
     * Method deserializes a JSON schema object (deserialized as a map) into
     * a ShexSchema object.
     *
     * @param map A JSON schema object
     * @return
     */
    protected ShexSchema handleSchema(Map map) {
        String start = ShexJsonldDeserializationUtils.getStartJsonField(map);
        List<String> imports = ShexJsonldDeserializationUtils.getImportsJsonArray(map);
        List<Map> shapes = ShexJsonldDeserializationUtils.getShapesJsonObject(map);
        List<SemAct> semActs = handleStartActs(map);
        if ( semActs == null )
            // The validator walks the start actions: never null.
            semActs = new ArrayList<>();
        List<ShapeDecl> deserializedShapeDecl = new ArrayList<>();
        for(Map shape : shapes) {
            deserializedShapeDecl.add(handleShapeDecl(shape));
        }
        ShexSchema schema = ShexSchema.shapes(null, null, null, shapeDeclIndex.get(start), deserializedShapeDecl, imports, semActs, new HashMap<>());//TODO Why not call it ShexSchema.create to follow pattern with other classes
        return schema;
    }

    /**
     * Method deserializes a JSON ShapeDecl object (deserialized as a map) into a
     * ShapeDecl object.
     *
     * @param map A JSON ShapeDecl object
     * @return
     */
    protected ShapeDecl handleShapeDecl(Map map) {
        String type = ShexJsonldDeserializationUtils.getTypeJsonField(map);
        if(!type.equalsIgnoreCase(JsonGEnum.SHAPE_DECL.getLabel())) {
            throw new RuntimeException("Unexpected type: " + type + ". Expecting a " + JsonGEnum.SHAPE_DECL.getLabel());
        }
        String id = ShexJsonldDeserializationUtils.getIdJsonField(map);
        boolean abstractShape = ShexJsonldDeserializationUtils.getAbstractJsonField(map);
        Map shapeExpressionJsonObject = ShexJsonldDeserializationUtils.getShapeExpressionJsonObject(map);
        ShapeExpr shapeExpr = handleShapeExpression(shapeExpressionJsonObject);
        //List<String> restricts = ShexJsonldDeserializationUtils.getRestrictsJsonArray(map);
        ShapeDecl shapeDecl = new ShapeDecl(ShexJsonldDeserializationUtils.resolveOrCreateNode(model, id), abstractShape, shapeExpr /*, restricts*/);
        shapeDeclIndex.put(id, shapeDecl); //Note: We store the ShapeDecl here for later resolution of the start reference as is required by the API.
        return shapeDecl;
    }

    /**
     * Method deserializes a JSON array of shape expressions into a
     * list of Shape expressions.
     *
     * @param jsonArray
     * @return
     */
    protected List<ShapeExpr> handleShapeExpressions(List<Object> jsonArray) {
        List<ShapeExpr> shapeExprs = new ArrayList<>();
        for(Object object : jsonArray) {
            if(object instanceof Map) {
                shapeExprs.add(handleShapeExpression((Map) object));
            } else {
                shapeExprs.add(ShapeExprRef.create(ShexJsonldDeserializationUtils.resolveOrCreateNode(model, (String)object)));
            }
        }
        return shapeExprs;
    }

    /**
     * Method deserializes a JSON ShapeExpr object (deserialized as a map) into a
     * descendant of ShapeExpr.
     *
     * @param map
     * @return
     */
    protected ShapeExpr handleShapeExpression(Map map) {
        ShapeExpr shapeExpr = null;
        String type = ShexJsonldDeserializationUtils.getTypeJsonField(map);
        if(type.equalsIgnoreCase(JsonGEnum.SHAPE.getLabel())) {
            shapeExpr = handleShape(map);
        } else if(type.equalsIgnoreCase(JsonGEnum.NODE_CONSTRAINT.getLabel())) {
            shapeExpr = handleNodeConstraint(model, map);
        } else if(type.equalsIgnoreCase(JsonGEnum.SHAPE_OR.getLabel())) {
            shapeExpr = handleShapeOr(map);
        } else if(type.equalsIgnoreCase(JsonGEnum.SHAPE_AND.getLabel())) {
            shapeExpr = handleShapeAnd(map);
        } else if(type.equalsIgnoreCase(JsonGEnum.SHAPE_NOT.getLabel())) {
            shapeExpr = handleShapeNot(map);
        } else if(type.equalsIgnoreCase(JsonGEnum.SHAPE_EXTERNAL.getLabel())) {
            shapeExpr = handleShapeExternal(map);
        } else if(type.equalsIgnoreCase(JsonGEnum.COLLECTION.getLabel())) {
            shapeExpr = handleCollection(map);
        } else {
            throw new RuntimeException("Unexpected type: " + type + ". Expecting a " + JsonGEnum.SHAPE.getLabel());
        }

        return shapeExpr;
    }

    protected static List<SemAct> handleSemActs(Map map) {
        List<SemAct> semActs = null;
        List<Map> semActJsonObjects = ShexJsonldDeserializationUtils.getSemActJsonArray(map);
        if(semActJsonObjects != null) {
            semActs = new ArrayList<>();
            for (Map semActJsonObject : semActJsonObjects) {
                semActs.add(handleSemAct(semActJsonObject));
            }
        }
        return semActs;
    }

    protected static List<SemAct> handleStartActs(Map map) {
        List<SemAct> semActs = null;
        List<Map> semActJsonObjects = ShexJsonldDeserializationUtils.getStartActsJsonArray(map);
        if(semActJsonObjects != null) {
            semActs = new ArrayList<>();
            for (Map semActJsonObject : semActJsonObjects) {
                semActs.add(handleSemAct(semActJsonObject));
            }
        }
        return semActs;
    }

    protected static SemAct handleSemAct(Map map) {
        String name = ShexJsonldDeserializationUtils.getNameJsonField(map);
        String code = ShexJsonldDeserializationUtils.getCodeJsonField(map);
        return new SemAct(name, code);
    }

    /**
     * Method deserializes a JSON NodeConstraint object (deserialized as a map) into
     * a NodeConstraint object.
     *
     * @param map
     * @return
     */
    protected static ShapeExpr handleNodeConstraint(Model model, Map map) {
        ShapeExpr shapeExpr = null;
        List<NodeConstraintComponent> components = new ArrayList<>();

        NodeConstraintComponent valueConstraint = handleValueConstraint(model, map);
        if(valueConstraint != null) { components.add(valueConstraint); }

        NodeConstraintComponent datatypeConstraint = handleDatatypeConstraint(map);
        if(datatypeConstraint != null) { components.add(datatypeConstraint); }

        List<SemAct> semActs = handleSemActs(map);

        shapeExpr = NodeConstraint.create(components, semActs);
        return shapeExpr;
    }

    /**
     * Method deserializes a JSON datatype constraint object (deserialized as a map) into
     * a DatatypeConstraint object.
     *
     * @param map
     */
    protected static DatatypeConstraint handleDatatypeConstraint(Map map) {
        DatatypeConstraint datatypeConstraint = null;
        String dataTypeJsonField = ShexJsonldDeserializationUtils.getDatatypeJsonField(map);
        if(dataTypeJsonField != null) {
            datatypeConstraint = new DatatypeConstraint(dataTypeJsonField);
        }
        return datatypeConstraint;
    }

    /**
     * Method deserializes a JSON datatype constraint object (deserialized as a map) into
     * a ValueConstraint.
     *
     *
     * @param map
     */
    protected static ValueConstraint handleValueConstraint(Model model, Map map) {
        ValueConstraint valueConstraint = null;
        List<Map> valuesJsonArray = ShexJsonldDeserializationUtils.getValuesJsonArray(map);
        if(valuesJsonArray != null) {
            List<ValueSetRange> valueSetRanges = new ArrayList<>();
            valueConstraint = new ValueConstraint(valueSetRanges);
            for(Object value : valuesJsonArray) {
                if(value instanceof Map) {
                    ValueSetRange valueSetRange = ShexJsonldDeserializationUtils.getValueJsonObject(model, (Map) value);
                    valueSetRanges.add(valueSetRange);
                } else {
                    ValueSetRange valueSetRange = new ValueSetRange((String) value, null, null, false);
                    valueSetRanges.add(valueSetRange);
                }
            }
        }
        return valueConstraint;
    }

    /**
     * Method deserializes a JSON Shape object (deserialized as a map) into
     * a Shape object.
     *
     * @param map A JSON Shape object
     * @return
     */
    protected Shape handleShape(Map map) {
        Object tripleExprJsonObject = ShexJsonldDeserializationUtils.getExpressionJsonObject(map);
        TripleExpr tripleExpr = null;
        if(tripleExprJsonObject != null) {
            tripleExpr = handleTripleExpression(tripleExprJsonObject);
        }
        return  Shape.newBuilder().shapeExpr(tripleExpr).build();
    }

    /**
     * Method deserializes a JSON ShapeNot object (deserialized as a map) into
     * a ShapeNot object.
     *
     * @param map A JSON Shape object
     * @return A Jena ShapeOr object
     */
    protected ShapeOr handleShapeOr(Map map) {
        List<Object> shapeExprArray = ShexJsonldDeserializationUtils.getShapeExpressionsJsonArray(map);
        List<ShapeExpr> shapeExprs = handleShapeExpressions(shapeExprArray);
        return (ShapeOr) ShapeOr.create(shapeExprs); //TODO ask Eric why the ShapeOr factory method not simply return ShapeOr rather than ShapeExpr.
    }

    /**
     * Method deserializes a JSON ShapeAnd object (deserialized as a map) into
     * a ShapeAnd object.
     *
     * @param map A JSON ShapeAnd object
     * @return
     */
    protected ShapeAnd handleShapeAnd(Map map) {
        List<Object> shapeExprArray = ShexJsonldDeserializationUtils.getShapeExpressionsJsonArray(map);
        List<ShapeExpr> shapeExprs = handleShapeExpressions(shapeExprArray);
        return (ShapeAnd) ShapeAnd.create(shapeExprs); //TODO ask Eric why the ShapeAnd factory method not simply return ShapeAnd rather than ShapeExpr.
    }

    /**
     * Method deserializes a JSON ShapeNot object (deserialized as a map) into
     * a ShapeNot object.
     *
     * @param map A JSON ShapeNot object
     * @return
     */
    protected ShapeNot handleShapeNot(Map map) {
        Map shapeExprJsonObject = ShexJsonldDeserializationUtils.getShapeExpressionJsonObject(map);
        ShapeExpr shapeExpr = handleShapeExpression(shapeExprJsonObject);
        return (ShapeNot) ShapeNot.create(shapeExpr); //TODO ask Eric why the ShapeNot factory method not simply return ShapeNot rather than ShapeExpr.
    }

    /**
     * RDF sequences: {"type": "Collection", "shapeExpr": ..., "min": m, "max": n}
     * (min defaults to 0, max to unbounded; -1 is unbounded).
     */
    protected ShapeExpr handleCollection(Map map) {
        Object shapeExprJson = map.get(JsonGEnum.SHAPE_EXPRESSION.getLabel());
        ShapeExpr shapeExpr;
        if ( shapeExprJson instanceof String str )
            // A reference to a shape declaration.
            shapeExpr = ShapeExprRef.create(ShexJsonldDeserializationUtils.resolveOrCreateNode(model, str));
        else if ( shapeExprJson instanceof Map m )
            shapeExpr = handleShapeExpression(m);
        else
            throw new RuntimeException("Collection: expected \"shapeExpr\" to be a shape expression or a reference: " + shapeExprJson);
        Integer min = ShexJsonldDeserializationUtils.getMinJsonField(map);
        Integer max = ShexJsonldDeserializationUtils.getMaxJsonField(map);
        int minV = ( min == null ) ? 0 : min;
        int maxV = ( max == null || max < 0 ) ? Cardinality.UNBOUNDED : max;
        return ShapeCollection.create(shapeExpr, minV, maxV);
    }

    /**
     * Method deserializes a JSON ShapeExternal object (deserialized as a map) into
     * a ShapeExternal object.
     *
     * @param map A JSON ShapeExternal object
     * @return
     */
    protected ShapeExternal handleShapeExternal(Map map) {//TODO Implement this
        return new ShapeExternal(); //TODO ask Eric why the ShapeExternal factory method not simply return a ShapeExternal rather than ShapeExpr.
    }

    /**
     * Method deserializes a JSON triple expression object (deserialized as a map) into
     * a descendant of TripleExpr.
     *
     * @param jsonObject A JSON triple expression object
     * @return
     */
    protected TripleExpr handleTripleExpression(Object jsonObject) {
        TripleExpr returnValue = null;
        if(jsonObject instanceof Map) {
            Map map = (Map)jsonObject;
            String type = ShexJsonldDeserializationUtils.getTypeJsonField(map);
            if (type.equalsIgnoreCase(JsonGEnum.TRIPLE_CONSTRAINT.getLabel())) {
                returnValue = handleTripleConstraint(map);
            } else if (type.equalsIgnoreCase(JsonGEnum.EACH_OF.getLabel())) {
                returnValue = handleEachOfConstraint(map);
            } else if(type.equalsIgnoreCase(JsonGEnum.ONE_OF.getLabel())) {
                returnValue = handleOneOfConstraint(map);
            } else {
                throw new RuntimeException("Unhandled type " + type);
            }
        } else {
            returnValue = TripleExprRef.create(ShexJsonldDeserializationUtils.resolveOrCreateNode(model, (String) jsonObject));
        }
        return returnValue;
    }

    /**
     * Method deserializes a JSON EachOf constraint object (deserialized as a map) into
     * a EachOf object.
     *
     * @param map A JSON EachOf object
     * @return
     */
    protected EachOf handleEachOfConstraint(Map map) {
        EachOf returnValue;
        List<TripleExpr> tripleExprs = new ArrayList<>();
        List<Map> expressionsJsonArray = ShexJsonldDeserializationUtils.getExpressionsJsonArray(map);
        for(Object expressionJsonObject : expressionsJsonArray) {
            tripleExprs.add(handleTripleExpression(expressionJsonObject));
        }
        List<SemAct> semActs = handleSemActs(map);
        returnValue = (EachOf) EachOf.create(tripleExprs, semActs);//TODO Why does EachOf.create() not return an EachOf?
        return returnValue;
    }

    /**
     * Method deserializes a JSON OneOf constraint object (deserialized as a map) into
     * a OneOf object.
     *
     * @param map A JSON OneOf object
     * @return
     */
    protected OneOf handleOneOfConstraint(Map map) {
        OneOf returnValue;
        List<TripleExpr> tripleExprs = new ArrayList<>();
        List<Map> expressionsJsonArray = ShexJsonldDeserializationUtils.getExpressionsJsonArray(map);
        for(Object expressionJsonObject : expressionsJsonArray) {
            tripleExprs.add(handleTripleExpression(expressionJsonObject));
        }
        List<SemAct> semActs = handleSemActs(map);
        returnValue = (OneOf) OneOf.create(tripleExprs, semActs);//TODO Why does OneOf.create() not return a OneOf?
        return returnValue;
    }

    /**
     * Method deserializes a JSON triple constraint (deserialized as a map) into
     * a TripleConstraint object or a TripleExprCardinality if a cardinality is stated
     * in the JSON object.
     *
     * @param map A JSON TripleConstraint object
     * @return May return either a TripleConstraint or a TripleExprCardinality
     */
    protected TripleExpr handleTripleConstraint(Map map) {
        TripleExpr returnValue;
        String predicate = ShexJsonldDeserializationUtils.getPredicateJsonField(map);
        Object valueExpressionJsonObject = ShexJsonldDeserializationUtils.getValueExpressionJsonObject(map);
        ShapeExpr valueExpr = null;
        if(valueExpressionJsonObject instanceof String) {
            valueExpr = ShapeExprRef.create(ShexJsonldDeserializationUtils.resolveOrCreateNode(model, (String)valueExpressionJsonObject));
        } else if(valueExpressionJsonObject instanceof Map) {
            valueExpr = handleShapeExpression((Map)valueExpressionJsonObject);
        }
        List<SemAct> semActs = handleSemActs(map);
        returnValue = TripleConstraint.create(null, ShexJsonldDeserializationUtils.resolveOrCreateNode(model, predicate), false, valueExpr, semActs);

        Cardinality cardinality = ShexJsonldDeserializationUtils.handleCardinality(map);
        if(cardinality != null) {
            returnValue = TripleExprCardinality.create(returnValue, cardinality, semActs);
        }
        return returnValue;
    }
}
