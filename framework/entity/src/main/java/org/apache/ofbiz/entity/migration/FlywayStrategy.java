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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.config.GenericConfigException;
import org.apache.ofbiz.base.config.ResourceHandler;
import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.SchemaCoverage;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;

/**
 * Runs Flyway migrations for every component that has {@code migrations/<vendor>/} coverage
 * matching this datasource's vendor.
 */
final class FlywayStrategy implements SchemaManagementStrategy, SchemaCoverage {

    private static final String MODULE = FlywayStrategy.class.getName();

    private static volatile Map<String, String> entityToComponentIndex;

    @Override
    public Map<String, ModelEntity> entitiesNotManagedByThisStrategy(Map<String, ModelEntity> entities, String vendor) {
        Map<String, String> index = entityToComponentIndex();
        Map<String, ModelEntity> uncovered = new HashMap<>();
        for (Map.Entry<String, ModelEntity> entry : entities.entrySet()) {
            String componentName = index.get(entry.getKey());
            if (componentName == null || !isCoveredByMigrations(componentName, vendor)) {
                uncovered.put(entry.getKey(), entry.getValue());
            }
        }
        return uncovered;
    }

    private static boolean isCoveredByMigrations(String componentName, String vendor) {
        try {
            Path componentRoot = MigrationSupport.resolveComponentRoot(componentName);
            return Files.isDirectory(MigrationSupport.migrationsDirectory(componentRoot, componentName, vendor));
        } catch (GenericConfigException e) {
            return false;
        }
    }

    private static synchronized Map<String, String> entityToComponentIndex() {
        if (entityToComponentIndex == null) {
            Map<String, String> index = new HashMap<>();
            ModelReader modelReader;
            try {
                modelReader = ModelReader.getModelReader("default");
            } catch (GenericEntityException e) {
                throw new IllegalStateException("Could not build the entity-to-component index for Flyway schema "
                        + "coverage - the \"default\" entity model could not be read, which almost certainly also "
                        + "breaks other parts of this boot; refusing to silently fall back to unfiltered auto-DDL", e);
            }
            for (ComponentConfig.EntityResourceInfo resourceInfo : ComponentConfig.getAllEntityResourceInfos("model")) {
                String componentName = resourceInfo.getComponentConfig().getComponentName();
                ResourceHandler handler = resourceInfo.createResourceHandler();
                Collection<String> entityNames = modelReader.getResourceHandlerEntities(handler);
                if (entityNames != null) {
                    for (String entityName : entityNames) {
                        index.put(entityName, componentName);
                    }
                }
            }
            entityToComponentIndex = index;
        }
        return entityToComponentIndex;
    }

    @Override
    public void apply(String delegatorName, MigrationSupport.JdbcTarget target, List<ComponentConfig> components)
            throws ContainerException {
        for (ComponentConfig component : componentsMatchingGroup(delegatorName, target, components)) {
            migrateComponent(component.rootLocation(), component.getComponentName(), delegatorName, target);
        }
    }

    /**
     * Validates, without running any migration SQL, that every component matching this target's
     * entity group is already fully migrated and undrifted — for {@code execution-mode=external},
     * where an operator or deploy pipeline is responsible for actually running migrations before
     * the application starts, and boot must refuse to proceed if that step was skipped or a
     * migration was added since it last ran.
     * @throws ContainerException naming the component if anything is pending or has drifted
     */
    void validate(String delegatorName, MigrationSupport.JdbcTarget target, List<ComponentConfig> components)
            throws ContainerException {
        for (ComponentConfig component : componentsMatchingGroup(delegatorName, target, components)) {
            validateComponent(component.rootLocation(), component.getComponentName(), delegatorName, target);
        }
    }

    private static void warnIfNoMigrationsForActiveVendor(Path componentRoot, String componentName, String vendor,
            Path migrationsDir) {
        if (MigrationSupport.hasAnyMigrationsDirectory(componentRoot, componentName)) {
            Debug.logWarning("Component '" + componentName + "' ships migrations but none for the active vendor '"
                    + vendor + "' (expected " + migrationsDir + "): Entity Engine auto-DDL will continue managing "
                    + "its tables on this datasource until a migration for this vendor is added", MODULE);
        }
    }

    private List<ComponentConfig> componentsMatchingGroup(String delegatorName, MigrationSupport.JdbcTarget target,
            List<ComponentConfig> components) throws ContainerException {
        List<ComponentConfig> matching = new ArrayList<>();
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
                if (MigrationSupport.hasAnyMigrationsDirectory(component.rootLocation(), component.getComponentName())) {
                    throw new ContainerException("Component '" + component.getComponentName()
                            + "' ships migrations but no entity group could be resolved for it (it declares no "
                            + "<entity-resource type=\"model\">); its migrations would be silently skipped");
                }
                continue;
            }
            if (componentGroups.contains(target.groupName())) {
                matching.add(component);
            }
        }
        return matching;
    }

    /**
     * Runs one component's migrations for one vendor against one JDBC connection. No-ops when the
     * component ships no migrations for this vendor, warning first if it ships migrations for other
     * vendors only — in that case its tables continue to be created and managed by Entity Engine
     * auto-DDL on this datasource, until a migration for it is added.
     * @param componentRoot the component's root directory
     * @param componentName the component name, used for the history table and error attribution
     * @param delegatorName the {@code <delegator>} name from {@code entityengine.xml}, used to resolve this
     *      component's real table names for schema fingerprinting
     * @param target the JDBC target to migrate against
     * @throws ContainerException if this component's migrations fail, or if the live schema has drifted since
     *      its last recorded fingerprint, so that OFBiz shuts down cleanly
     */
    void migrateComponent(Path componentRoot, String componentName, String delegatorName, MigrationSupport.JdbcTarget target)
            throws ContainerException {
        String vendor = target.vendor();
        Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, componentName, vendor);
        if (!Files.isDirectory(migrationsDir)) {
            warnIfNoMigrationsForActiveVendor(componentRoot, componentName, vendor, migrationsDir);
            return;
        }
        String historyTable = MigrationSupport.historyTableName(componentName);
        Debug.logInfo("Running migrations for component '" + componentName
                + "' (vendor=" + vendor + ") from " + migrationsDir, MODULE);
        try (Connection conn = DriverManager.getConnection(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword())) {
            String schemaName = MigrationSupport.resolveSchemaName(target, conn);
            Set<String> tableNames = SchemaFingerprint.resolveComponentTableNames(delegatorName, componentName);
            String storedFingerprint = SchemaFingerprint.load(conn, componentName);
            if (storedFingerprint != null) {
                String liveFingerprint = SchemaFingerprint.compute(conn, schemaName, tableNames);
                if (!storedFingerprint.equals(liveFingerprint)) {
                    throw new ContainerException("Component '" + componentName + "' schema has drifted since its last "
                            + "Flyway-recorded state (fingerprint mismatch) - run SchemaDriftAuditor to see what changed,"
                            + " then baselineMigration to reconcile before this component's migrations can run again");
                }
            }
            ComponentMigrator migrator = new ComponentMigrator(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword(),
                    migrationsDir, historyTable);
            migrator.migrate();
            SchemaFingerprint.store(conn, componentName, SchemaFingerprint.compute(conn, schemaName, tableNames));
        } catch (ContainerException e) {
            throw e;
        } catch (Exception e) {
            String errorMessage = "Migration failed for component '" + componentName + "'";
            Debug.logError(e, errorMessage, MODULE);
            throw new ContainerException(errorMessage, e);
        }
    }

    void validateComponent(Path componentRoot, String componentName, String delegatorName, MigrationSupport.JdbcTarget target)
            throws ContainerException {
        String vendor = target.vendor();
        Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, componentName, vendor);
        if (!Files.isDirectory(migrationsDir)) {
            warnIfNoMigrationsForActiveVendor(componentRoot, componentName, vendor, migrationsDir);
            return;
        }
        String historyTable = MigrationSupport.historyTableName(componentName);
        try (Connection conn = DriverManager.getConnection(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword())) {
            String schemaName = MigrationSupport.resolveSchemaName(target, conn);
            Set<String> tableNames = SchemaFingerprint.resolveComponentTableNames(delegatorName, componentName);
            String storedFingerprint = SchemaFingerprint.load(conn, componentName);
            if (storedFingerprint == null) {
                throw new ContainerException("Component '" + componentName + "' has migrations but was never migrated"
                        + " or baselined on this datasource - run the external migration step or baselineMigration"
                        + " before starting the application in external execution-mode");
            }
            String liveFingerprint = SchemaFingerprint.compute(conn, schemaName, tableNames);
            if (!storedFingerprint.equals(liveFingerprint)) {
                throw new ContainerException("Component '" + componentName + "' schema has drifted since its last "
                        + "Flyway-recorded state - run SchemaDriftAuditor, then baselineMigration to reconcile");
            }
            ComponentMigrator migrator = new ComponentMigrator(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword(),
                    migrationsDir, historyTable);
            if (migrator.hasPendingMigrations()) {
                throw new ContainerException("Component '" + componentName + "' has pending migrations that were not"
                        + " applied - run the external migration step before starting the application");
            }
        } catch (ContainerException e) {
            throw e;
        } catch (Exception e) {
            throw new ContainerException("Could not validate schema state for component '" + componentName + "'", e);
        }
    }
}
