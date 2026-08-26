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
import org.apache.ofbiz.base.container.Container;
import org.apache.ofbiz.base.container.ContainerConfig;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.start.StartupCommand;
import org.apache.ofbiz.entity.GenericEntityConfException;
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

    private String name;
    private String delegatorName;

    @Override
    public void init(List<StartupCommand> ofbizCommands, String name, String configFile) throws ContainerException {
        this.name = name;
        ContainerConfig.Configuration cc = ContainerConfig.getConfiguration(name);
        this.delegatorName = ContainerConfig.getPropertyValue(cc, "delegator-name", "default");
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
                MigrationSupport.JdbcTarget target =
                        MigrationSupport.resolveJdbcTarget(groupMap.getGroupName(), groupMap.getDatasourceName());
                if (target == null) {
                    continue;
                }
                strategy.apply(delegatorName, target, components);
            }
            return true;
        } catch (GenericEntityConfException e) {
            throw new ContainerException(e);
        }
    }

    static SchemaManagementStrategy resolveStrategy(String schemaManagementStrategyValue) {
        if ("flyway".equals(schemaManagementStrategyValue)) {
            return FLYWAY_STRATEGY;
        }
        return AUTO_DDL_STRATEGY;
    }

    @Override
    public void stop() throws ContainerException {
    }

    @Override
    public String getName() {
        return name;
    }
}
