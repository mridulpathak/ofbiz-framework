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
import java.util.Set;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.container.Container;
import org.apache.ofbiz.base.container.ContainerConfig;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.start.StartupCommand;
import org.apache.ofbiz.entity.GenericEntityConfException;
import org.apache.ofbiz.entity.SchemaCoverage;
import org.apache.ofbiz.entity.SchemaCoverageRegistry;
import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.apache.ofbiz.entity.config.model.GroupMap;

/**
 * Runs, once per boot and before {@link org.apache.ofbiz.entity.DelegatorContainer}, any
 * Flyway migrations found under each component's {@code migrations/<vendor>/} directory,
 * where {@code <vendor>} matches the active datasource's {@code field-type-name}.
 */
public class MigrationContainer implements Container {

    private static final SchemaManagementStrategy AUTO_DDL_STRATEGY = new AutoDdlStrategy();
    private static final SchemaManagementStrategy FLYWAY_STRATEGY = new FlywayStrategy();
    private static final Set<String> VALID_EXECUTION_MODES = Set.of("embedded", "external");

    private String name;
    private String delegatorName;
    private String executionMode;

    @Override
    public void init(List<StartupCommand> ofbizCommands, String name, String configFile) throws ContainerException {
        this.name = name;
        ContainerConfig.Configuration cc = ContainerConfig.getConfiguration(name);
        this.delegatorName = ContainerConfig.getPropertyValue(cc, "delegator-name", "default");
        this.executionMode = ContainerConfig.getPropertyValue(cc, "execution-mode", "embedded");
        validateExecutionMode(this.executionMode);
    }

    @Override
    public boolean start() throws ContainerException {
        try {
            List<ComponentConfig> components = ComponentConfig.components().toList();
            DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
            if (delegator == null) {
                throw new GenericEntityConfException("No <delegator> named '" + delegatorName + "' found in entityengine.xml");
            }
            for (GroupMap groupMap : delegator.getGroupMapList()) {
                // Cheap, credential-free check first: only resolve real JDBC info (including password
                // decryption) for datasources whose strategy actually resolves to Flyway. A datasource
                // left at the default "auto-ddl" must never trigger credential resolution here.
                Datasource datasource = EntityConfig.getDatasource(groupMap.getDatasourceName());
                String strategyValue = datasource == null ? null : datasource.getSchemaManagementStrategy();
                SchemaManagementStrategy strategy = resolveStrategy(strategyValue);
                if (strategy == AUTO_DDL_STRATEGY) {
                    continue;
                }
                if (strategy instanceof SchemaCoverage coverage) {
                    SchemaCoverageRegistry.register(groupMap.getDatasourceName(), coverage);
                }
                MigrationSupport.JdbcTarget target =
                        MigrationSupport.resolveJdbcTarget(groupMap.getGroupName(), groupMap.getDatasourceName());
                if (target == null) {
                    continue;
                }
                if ("external".equals(executionMode)) {
                    strategy.validate(delegatorName, target, components);
                } else {
                    strategy.apply(delegatorName, target, components);
                }
            }
            return true;
        } catch (GenericEntityConfException e) {
            throw new ContainerException(e);
        }
    }

    static SchemaManagementStrategy resolveStrategy(String schemaManagementStrategyValue) throws ContainerException {
        if (schemaManagementStrategyValue == null || schemaManagementStrategyValue.isBlank()
                || "auto-ddl".equals(schemaManagementStrategyValue)) {
            return AUTO_DDL_STRATEGY;
        }
        if ("flyway".equals(schemaManagementStrategyValue)) {
            return FLYWAY_STRATEGY;
        }
        throw new ContainerException("Invalid schema-management-strategy '" + schemaManagementStrategyValue
                + "' - must be 'auto-ddl' (or omitted) or 'flyway' (check the relevant <datasource> in entityengine.xml)");
    }

    static void validateExecutionMode(String executionMode) throws ContainerException {
        if (!VALID_EXECUTION_MODES.contains(executionMode)) {
            throw new ContainerException("Invalid execution-mode '" + executionMode + "' - must be one of "
                    + VALID_EXECUTION_MODES + " (check the migration-container's configuration in ofbiz-component.xml)");
        }
    }

    @Override
    public void stop() throws ContainerException {
    }

    @Override
    public String getName() {
        return name;
    }
}
