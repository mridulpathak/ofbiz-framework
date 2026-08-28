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

import java.util.Map;

import org.apache.ofbiz.entity.model.ModelEntity;

/**
 * Implemented by a schema-management strategy (e.g. Flyway-based migrations) that wants Entity
 * Engine's own auto-DDL to keep managing whichever entities it does not itself cover, instead of
 * auto-DDL being suppressed wholesale for an entire datasource. Deliberately defined here, in core
 * {@code org.apache.ofbiz.entity}, rather than in any specific strategy's own package: this is the
 * one seam {@link GenericDelegator} is allowed to depend on, so it never needs to know which
 * concrete strategy (Flyway or otherwise) is actually in use — see {@link SchemaCoverageRegistry}.
 */
public interface SchemaCoverage {

    /**
     * Returns the subset of {@code entities} that this strategy does not manage, so Entity Engine's
     * auto-DDL can continue managing them. Implementations must not mutate {@code entities}.
     * @param entities every entity in the datasource's entity group, keyed by entity name
     * @param vendor the active datasource's {@code field-type-name}
     * @return the entities this strategy leaves for auto-DDL to manage
     */
    Map<String, ModelEntity> entitiesNotManagedByThisStrategy(Map<String, ModelEntity> entities, String vendor);
}
