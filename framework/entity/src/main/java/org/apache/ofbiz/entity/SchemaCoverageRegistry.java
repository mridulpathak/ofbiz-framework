/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.apache.ofbiz.entity;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Maps a datasource name to whichever {@link SchemaCoverage} strategy is actually managing schema
 * changes for it, if any. Populated at boot by whatever container resolves each datasource's
 * configured strategy (e.g. {@code org.apache.ofbiz.entity.migration.MigrationContainer}, which
 * runs before {@link org.apache.ofbiz.base.container.Container}-ordered delegator initialization) —
 * this class itself has no knowledge of any specific strategy. {@link GenericDelegator} reads this
 * registry instead of depending on any strategy-specific package directly.
 */
public final class SchemaCoverageRegistry {

    private static final Map<String, SchemaCoverage> REGISTRY = new ConcurrentHashMap<>();

    private SchemaCoverageRegistry() {
    }

    /**
     * Registers the {@link SchemaCoverage} strategy managing schema changes for a datasource.
     * Registering again for the same datasource name replaces the previous registration.
     * @param datasourceName the {@code <datasource name="...">} value from {@code entityengine.xml}
     * @param coverage the strategy managing this datasource's schema changes
     */
    public static void register(String datasourceName, SchemaCoverage coverage) {
        REGISTRY.put(datasourceName, coverage);
    }

    /**
     * Looks up the {@link SchemaCoverage} strategy registered for a datasource, if any.
     * @param datasourceName the {@code <datasource name="...">} value from {@code entityengine.xml}
     * @return the registered strategy, or empty if none is registered (e.g. the datasource is
     *      auto-ddl-managed, or nothing has registered for it yet)
     */
    public static Optional<SchemaCoverage> lookup(String datasourceName) {
        return Optional.ofNullable(REGISTRY.get(datasourceName));
    }

    /**
     * Clears every registration. Test-only — production code has no reason to ever clear this.
     */
    static void clear() {
        REGISTRY.clear();
    }
}
