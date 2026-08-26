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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityException;

/**
 * Runs Flyway migrations for every component that has {@code migrations/<vendor>/} coverage
 * matching this datasource's vendor.
 */
final class FlywayStrategy implements SchemaManagementStrategy {

    private static final String MODULE = FlywayStrategy.class.getName();

    @Override
    public void apply(String delegatorName, MigrationSupport.JdbcTarget target, List<ComponentConfig> components)
            throws ContainerException {
        for (ComponentConfig component : components) {
            Set<String> componentGroups;
            try {
                componentGroups = MigrationSupport.resolveComponentEntityGroups(delegatorName, component.getComponentName());
            } catch (GenericEntityException e) {
                throw new ContainerException("Could not resolve entity groups for component '"
                        + component.getComponentName() + "'", e);
            }
            componentGroups.remove(null);
            if (componentGroups.isEmpty()) {
                if (MigrationSupport.hasAnyMigrationsDirectory(component.rootLocation())) {
                    throw new ContainerException("Component '" + component.getComponentName()
                            + "' ships migrations but no entity group could be resolved for it (it declares no "
                            + "<entity-resource type=\"model\">); its migrations would be silently skipped");
                }
                continue;
            }
            if (!componentGroups.contains(target.groupName())) {
                continue;
            }
            migrateComponent(component.rootLocation(), component.getComponentName(), target.vendor(),
                    target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword());
        }
    }

    /**
     * Runs one component's migrations for one vendor against one JDBC connection. No-ops when the
     * component ships no migrations for this vendor, warning first if it ships migrations for other
     * vendors only — in that case the Entity Engine's auto-DDL will create the component's tables
     * while Flyway silently never manages them on this datasource.
     * @param componentRoot the component's root directory
     * @param componentName the component name, used for the history table and error attribution
     * @param vendor the active datasource's {@code field-type-name}
     * @param jdbcUrl the target JDBC URL
     * @param jdbcUsername the target JDBC username
     * @param jdbcPassword the target JDBC password
     * @throws ContainerException if this component's migrations fail, so that OFBiz shuts down cleanly
     */
    void migrateComponent(Path componentRoot, String componentName, String vendor,
            String jdbcUrl, String jdbcUsername, String jdbcPassword) throws ContainerException {
        Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, vendor);
        if (!Files.isDirectory(migrationsDir)) {
            if (MigrationSupport.hasAnyMigrationsDirectory(componentRoot)) {
                Debug.logWarning("Component '" + componentName + "' ships migrations but none for the active vendor '"
                        + vendor + "' (expected " + migrationsDir + "): its tables will be created by Entity Engine"
                        + " auto-DDL and never tracked by Flyway on this datasource", MODULE);
            }
            return;
        }
        String historyTable = MigrationSupport.historyTableName(componentName);
        Debug.logInfo("Running migrations for component '" + componentName
                + "' (vendor=" + vendor + ") from " + migrationsDir, MODULE);
        ComponentMigrator migrator = new ComponentMigrator(jdbcUrl, jdbcUsername, jdbcPassword, migrationsDir, historyTable);
        try {
            migrator.migrate();
        } catch (Exception e) {
            String errorMessage = "Migration failed for component '" + componentName + "'";
            Debug.logError(e, errorMessage, MODULE);
            throw new ContainerException(errorMessage, e);
        }
    }
}
