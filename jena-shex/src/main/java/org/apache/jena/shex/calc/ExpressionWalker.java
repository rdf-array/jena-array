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

package org.apache.jena.shex.calc;

import org.apache.jena.graph.Node;
import org.apache.jena.shex.ShapeDecl;
import org.apache.jena.shex.expressions.*;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/** Walks recursively through an expression (shape expression or triple expression) applying visitors on its sub-expressions.
 * A walker is constructed by a {@link Builder}.
 * Add visitors to the walker using {@link Builder#processShapeExprsWith(VoidShapeExprVisitor)} and {@link Builder#processTripleExprsWith(VoidTripleExprVisitor)}.
 * There is no guarantee on the order in which the visitors will be called on the sub-expressions.
 *
 * A walker can be parametrized in several ways:
 * <ul>
 *  <li>dereference or not triple expression references. By default, the walker will not walk through triple expression references, unless {@link Builder#followTripleExprRefs(Function)}} is set.</li>
 *  <li>dereference or not shape expression references. By default, the walker will not walk through shape expression references, See {@link Builder#followShapeExprRefs(Function)} is set.</li>
 *  <li>recurse or not to triple expressions while walking a shape expression. By default, the walker stops exploration when a {@link Shape} is encountered, unless {@link Builder#traverseShapes()} is set, in which case the walker will walk through the triple expression of that shape. </li>
 *  <li>recurse or not to shape expressions while walking a triple expression. By default, the walker stops exploration when a {@link TripleConstraint} is encountered, unless {@link Builder#traverseTripleConstraints()} is set, in which case the walker will walk through the value expression of that triple constraint. </li>
 * </ul>
 *
 */
public class ExpressionWalker implements VoidTripleExprVisitor, VoidShapeExprVisitor {

    private final List<VoidShapeExprVisitor> shapeExprProcessors;
    private final List<VoidTripleExprVisitor> tripleExprProcessors;
    private final boolean traverseShapes;
    private final boolean traverseTripleConstraints;
    private final boolean followShapeExprRefs;
    private final boolean followTripleExprRefs;
    private final Function<Node, TripleExpr> tripleExprDereferencer;
    private final Function<Node, ShapeDecl> shapeDeclDereferencer;
    private final Set<Class<? extends Expression>> dontRecurseInto;
    private final Set<Predicate<Expression>> dontRecurseIf;

    private ExpressionWalker(List<VoidShapeExprVisitor> shapeExprProcessors, List<VoidTripleExprVisitor> tripleExprProcessors,
                             boolean traverseShapes, boolean traverseTripleConstraints,
                             boolean followShapeExprRefs, boolean followTripleExprRefs,
                             Function<Node, ShapeDecl> shapeDeclDereferencer,
                             Function<Node, TripleExpr> tripleExprDereferencer,
                             Set<Class<? extends Expression>> dontRecurseInto,
                             Set<Predicate<Expression>> dontRecurseIf) {
        this.shapeExprProcessors = shapeExprProcessors;
        this.tripleExprProcessors = tripleExprProcessors;
        this.traverseShapes = traverseShapes;
        this.traverseTripleConstraints = traverseTripleConstraints;
        this.followShapeExprRefs = followShapeExprRefs;
        this.followTripleExprRefs = followTripleExprRefs;
        this.shapeDeclDereferencer = shapeDeclDereferencer;
        this.tripleExprDereferencer = tripleExprDereferencer;
        this.dontRecurseInto = dontRecurseInto;
        this.dontRecurseIf = dontRecurseIf;
    }

    private void process(TripleExpr tripleExpr) {
        tripleExprProcessors.forEach(tripleExpr::visit);
    }

    private void process(ShapeExpr shapeExpr) {
        shapeExprProcessors.forEach(shapeExpr::visit);
    }

    private boolean recurse(Expression expr) {
        if (dontRecurseInto.contains(expr.getClass())) {
            return false;
        }
        for (Predicate<Expression> predicate : dontRecurseIf) {
            if (predicate.test(expr)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void visit(ShapeAnd shapeAnd) {
        process(shapeAnd);
        if (recurse(shapeAnd))
            shapeAnd.getShapeExprs().forEach(sh->sh.visit(this));
    }

    @Override
    public void visit(ShapeOr shapeOr) {
        process(shapeOr);
        if (recurse(shapeOr))
            shapeOr.getShapeExprs().forEach(sh->sh.visit(this));
    }

    @Override
    public void visit(ShapeNot shapeNot) {
        process(shapeNot);
        if (recurse(shapeNot))
            shapeNot.getShapeExpr().visit(this);
    }

    @Override
    public void visit(ShapeCollection shapeCollection) {
        process(shapeCollection);
        if (recurse(shapeCollection))
            shapeCollection.getShapeExpr().visit(this);
    }

    /**
     * @throws UndefinedReferenceException If the visited reference is undefined.
     */
    @Override
    public void visit(ShapeExprRef shapeExprRef) {
        process(shapeExprRef);
        if (followShapeExprRefs && recurse(shapeExprRef)) {
           ShapeDecl shapeDecl = shapeDeclDereferencer.apply(shapeExprRef.getLabel());
            if (shapeDecl == null)
                throw new UndefinedReferenceException(shapeExprRef.getLabel(), "Undefined tripleExprRef " + shapeExprRef.getLabel());
           shapeDecl.getShapeExpr().visit(this);
        }
    }

    @Override
    public void visit(ShapeExternal shapeExternal) {
        process(shapeExternal);
    }

    @Override
    public void visit(Shape shape) {
        process(shape);
        if (traverseShapes && recurse(shape))
            shape.getTripleExpr().visit(this);
    }

    @Override
    public void visit(NodeConstraint nodeConstraint) {
        process(nodeConstraint);
    }

    @Override
    public void visit(TripleExprCardinality tripleExprCardinality) {
        process(tripleExprCardinality);
        if (recurse(tripleExprCardinality))
            tripleExprCardinality.getSubExpr().visit(this);
    }

    @Override
    public void visit(EachOf eachOf) {
        process(eachOf);
        if (recurse(eachOf))
            eachOf.getTripleExprs().forEach(sh->sh.visit(this));
    }

    @Override
    public void visit(OneOf oneOf) {
        process(oneOf);
        if (recurse(oneOf))
            oneOf.getTripleExprs().forEach(sh->sh.visit(this));
    }

    @Override
    public void visit(TripleExprEmpty tripleExprEmpty) {
        process(tripleExprEmpty);
    }

    /**
     * @throws UndefinedReferenceException If the visited reference is undefined.
     */
    @Override
    public void visit(TripleExprRef tripleExprRef) {
        process(tripleExprRef);
        if (followTripleExprRefs && recurse(tripleExprRef)) {
            TripleExpr tripleExpr = tripleExprDereferencer.apply(tripleExprRef.getLabel());
            if (tripleExpr == null)
                throw new UndefinedReferenceException(tripleExprRef.getLabel(), "Undefined tripleExprRef " + tripleExprRef.getLabel());
            tripleExpr.visit(this);
        }
    }

    @Override
    public void visit(TripleConstraint tripleConstraint) {
        process(tripleConstraint);
        if (traverseTripleConstraints && recurse(tripleConstraint))
            tripleConstraint.getValueExpr().visit(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Allows to construct an #ExpressionWalker */
    public static class Builder {
        private final List<VoidShapeExprVisitor> _shapeExprProcessors = new ArrayList<>();
        private final List<VoidTripleExprVisitor> _tripleExprProcessors = new ArrayList<>();
        private boolean _traverseShapes = false;
        private boolean _traverseTripleConstraints = false;
        private boolean _followShapeExprRefs = false;
        private boolean _followTripleExprRefs = false;
        private Function<Node, TripleExpr> _tripleExprDereferencer = null;
        private Function<Node, ShapeDecl> _shapeExprDereferencer = null;
        private Set<Class<? extends Expression>> _dontRecurseInto = null;
        private Set<Predicate<Expression>> _dontRecurseIf = null;

        private Builder () {}

        /** Adds a shape expression visitor that will be used on shape expressions. */
        public Builder processShapeExprsWith(VoidShapeExprVisitor shapeExprProcessor) {
            this._shapeExprProcessors.add(shapeExprProcessor);
            return this;
        }

        /** Adds a triple expression visitor that will be used on triple expressions. */
        public Builder processTripleExprsWith(VoidTripleExprVisitor tripleExprProcessor) {
            this._tripleExprProcessors.add(tripleExprProcessor);
            return this;
        }

        /** Indicates that the walker must traverse shapes.
         * That is, when a Shape is encountered, the walker walks through the triple expression of that shape. */
        public Builder traverseShapes () {
            this._traverseShapes = true;
            return this;
        }

        /** Indicates that the walker must traverse triple constraints.
         * That is, when a triple constraint is encountered, the walker walks to the value expression of this triple constraint. */
        public Builder traverseTripleConstraints () {
            this._traverseTripleConstraints = true;
            return this;
        }

        /** Indicates that the walker must dereference shape expression references.
         * That is, when a shape expression reference is encountered, the walker must walk through the shape expression referenced by that reference.
         * @param shapeExprDereferencer allows to retrieve the shape expression from its label
         */
        public Builder followShapeExprRefs(Function<Node, ShapeDecl> shapeExprDereferencer) {
            this._followShapeExprRefs = true;
            this._shapeExprDereferencer = shapeExprDereferencer;
            return this;
        }

        /** Indicates that the walker must dereference triple expression references.
         * That is, when a triple expression reference is encountered, the walker must walk through the triple expression referenced by that reference.
         * @param tripleExprDereferencer allows to retrieve the triple expression from its label
         */
        public Builder followTripleExprRefs(Function<Node, TripleExpr> tripleExprDereferencer) {
            this._followTripleExprRefs = true;
            this._tripleExprDereferencer = tripleExprDereferencer;
            return this;
        }

        static final Set<Class<? extends Expression>> recSubclasess =
                Set.of(ShapeAnd.class, ShapeOr.class, ShapeNot.class,
                        EachOf.class, OneOf.class, TripleExprCardinality.class);

        /** Indicate not to make recursive calls into expressions of this type.
         * The parameter must be an expression type that has sub-expressions
         * (among {@link ShapeAnd}, {@link ShapeOr}, {@link ShapeNot},
         * {@link TripleExprCardinality}, {@link EachOf}, {@link OneOf}). */
        public Builder dontRecurseInto(Class<? extends Expression> clazz) {
            if (! recSubclasess.contains(clazz))
                throw new IllegalArgumentException("Not an expression type with sub-expressions: " + clazz);
            if (this._dontRecurseInto == null)
                this._dontRecurseInto = new HashSet<>();
            this._dontRecurseInto.add(clazz);
            return this;
        }

        /** Indicate not to make recursive calls into expressions that satisfy this condition. */
        public Builder dontRecurseIf(Predicate<Expression> dontRecurseCondition) {
            if (this._dontRecurseIf == null)
                this._dontRecurseIf = new HashSet<>();
            this._dontRecurseIf.add(dontRecurseCondition);
            return this;
        }

        /** Builds the instance of the expression walker. */
        public ExpressionWalker build() {
            return new ExpressionWalker(_shapeExprProcessors, _tripleExprProcessors,
                    _traverseShapes, _traverseTripleConstraints,
                    _followShapeExprRefs, _followTripleExprRefs,
                    _shapeExprDereferencer, _tripleExprDereferencer,
                    _dontRecurseInto == null ? Collections.emptySet() : _dontRecurseInto,
                    _dontRecurseIf == null ? Collections.emptySet() : _dontRecurseIf);
        }

    }

}
