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
package org.apache.ofbiz.entity.migration;

import java.util.List;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.container.ContainerException;

/**
 * How a single datasource's schema is managed at boot: today's Entity Engine auto-DDL, Flyway
 * migrations, or (in the future) something else — selected per datasource via the
 * {@code schema-management-strategy} attribute in {@code entityengine.xml}.
 */
interface SchemaManagementStrategy {

    /**
     * Applies this strategy to one resolved JDBC target.
     * @param delegatorName the {@code <delegator>} name the components' entity models and entity-group
     *      mappings should be resolved against
     * @param target the datasource/group to manage
     * @param components every loaded component, for the strategy to filter and migrate as it sees fit
     * @throws ContainerException if applying the strategy fails, so OFBiz can shut down cleanly
     */
    void apply(String delegatorName, MigrationSupport.JdbcTarget target, List<ComponentConfig> components) throws ContainerException;

    /**
     * Validates, without applying any changes, that this target is already in the state this
     * strategy expects — for {@code execution-mode=external}, where boot must refuse to proceed if
     * an out-of-band step (e.g. running migrations before boot) was skipped, or (for a strategy with
     * nothing to validate, like auto-DDL) as a trivial no-op.
     * @param delegatorName the {@code <delegator>} name the components' entity models and entity-group
     *      mappings should be resolved against
     * @param target the datasource/group to validate
     * @param components every loaded component, for the strategy to filter and check as it sees fit
     * @throws ContainerException if validation fails, so OFBiz can shut down cleanly
     */
    void validate(String delegatorName, MigrationSupport.JdbcTarget target, List<ComponentConfig> components) throws ContainerException;
}
