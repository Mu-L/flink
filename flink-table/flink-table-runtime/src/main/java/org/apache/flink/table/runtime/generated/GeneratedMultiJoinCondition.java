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

package org.apache.flink.table.runtime.generated;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;

/** Describes a generated {@link MultiJoinCondition}. */
public class GeneratedMultiJoinCondition extends GeneratedClass<MultiJoinCondition> {

    private static final long serialVersionUID = 1L;

    @VisibleForTesting
    public GeneratedMultiJoinCondition(String className, String code, Object[] references) {
        super(className, code, references, new Configuration());
    }

    /**
     * Creates a GeneratedMultiJoinCondition.
     *
     * @param className class name of the generated MultiJoinCondition.
     * @param code code of the generated MultiJoinCondition.
     * @param references referenced objects of the generated MultiJoinCondition.
     * @param conf configuration when generating MultiJoinCondition.
     */
    public GeneratedMultiJoinCondition(
            String className, String code, Object[] references, ReadableConfig conf) {
        super(className, code, references, conf);
    }
}
