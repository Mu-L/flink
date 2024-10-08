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

package org.apache.flink.api.common;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.java.typeutils.runtime.kryo.KryoSerializer;

import com.esotericsoftware.kryo.Serializer;

import java.io.Serializable;

/**
 * The wrapper to make serializer serializable.
 *
 * <p>This can be removed after {@link KryoSerializer} only allow serializer class.
 */
@Internal
public class SerializableSerializer<T extends Serializer<?> & Serializable>
        implements Serializable {
    private static final long serialVersionUID = 4687893502781067189L;

    private T serializer;

    public SerializableSerializer(T serializer) {
        this.serializer = serializer;
    }

    public T getSerializer() {
        return serializer;
    }
}
