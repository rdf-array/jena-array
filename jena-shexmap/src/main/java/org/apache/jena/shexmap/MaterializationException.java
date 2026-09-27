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

package org.apache.jena.shexmap;

import java.util.List;
import java.util.stream.Collectors;

/** No alternative materializes the requested shape from the bindings. */
public class MaterializationException extends ShExMapException {
    private final List<Materializer.Failure> failures;

    public MaterializationException(String message) {
        this(message, List.of());
    }

    public MaterializationException(String message, List<Materializer.Failure> failures) {
        super(describe(message, failures));
        this.failures = List.copyOf(failures);
    }

    /** The dead ends met on the way, deepest last. */
    public List<Materializer.Failure> getFailures() { return failures; }

    private static String describe(String message, List<Materializer.Failure> failures) {
        if (failures.isEmpty())
            return message;
        List<Materializer.Failure> last = failures.subList(Math.max(0, failures.size() - 3), failures.size());
        return message + "; deepest failures: " + last.stream().map(Object::toString).collect(Collectors.joining("; "));
    }
}
