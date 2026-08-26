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
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.component.ComponentLoaderConfig;
import org.apache.ofbiz.base.config.GenericConfigException;
import org.apache.ofbiz.base.config.ResourceHandler;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityConfException;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.apache.ofbiz.entity.config.model.InlineJdbc;
import org.apache.ofbiz.entity.model.ModelGroupReader;
import org.apache.ofbiz.entity.model.ModelReader;

/**
 * Configuration lookups shared by the two entry points into Flyway-based component migrations:
 * {@link MigrationContainer}, which migrates every component at boot, and
 * {@link BaselineComponentMigration}, the operator-invoked CLI that baselines a single component.
 * Kept in one place so both resolve delegators, datasources, vendors, history-table names and
 * {@code migrations/<vendor>/} paths identically.
 */
final class MigrationSupport {

    private static final String MODULE = MigrationSupport.class.getName();
    private static final String MIGRATIONS_DIRECTORY_NAME = "migrations";

    private MigrationSupport() {
    }

    /**
     * One migratable JDBC connection resolved from a datasource definition: everything
     * {@link ComponentMigrator} needs, plus the vendor that selects a component's migrations
     * sub-directory, plus the entity group the datasource's group-map serves (so a component's
     * migrations only run against the datasource(s) its own entities actually belong to).
     */
    record JdbcTarget(String groupName, String datasourceName, String vendor, String jdbcUrl, String jdbcUsername,
            String jdbcPassword) { }

    /**
     * Resolves a single datasource into a migratable target.
     * @param groupName the entity group name the datasource is mapped to, e.g. {@code org.apache.ofbiz}
     * @param datasourceName the {@code <datasource>} name from {@code entityengine.xml}
     * @return the target, or {@code null} when the datasource is unknown or has no {@code <inline-jdbc>}
     * @throws GenericEntityConfException if the configured JDBC password cannot be resolved
     */
    static JdbcTarget resolveJdbcTarget(String groupName, String datasourceName) throws GenericEntityConfException {
        Datasource datasource = EntityConfig.getDatasource(datasourceName);
        if (datasource == null) {
            Debug.logWarning("Datasource '" + datasourceName + "' is not defined in entityengine.xml, skipping migrations", MODULE);
            return null;
        }
        InlineJdbc jdbc = datasource.getInlineJdbc();
        if (jdbc == null) {
            Debug.logInfo("Datasource '" + datasourceName
                    + "' has no <inline-jdbc>, skipping migrations (JNDI datasources not yet supported)", MODULE);
            return null;
        }
        return new JdbcTarget(groupName, datasourceName, datasource.getFieldTypeName(), jdbc.getJdbcUri(),
                jdbc.getJdbcUsername(), EntityConfig.getJdbcPassword(jdbc));
    }

    /**
     * Resolves the entity group(s) a component's entities belong to, so a caller can tell whether a
     * given datasource's group-map is one this component should actually be migrated against.
     * @param delegatorName the {@code <delegator>} name from {@code entityengine.xml}, used to resolve
     *      both the component's entity model and its entity-group mappings
     * @param componentName the OFBiz component name
     * @return the distinct entity group name(s) used by this component's entities; empty if the
     *      component defines no entities
     * @throws GenericEntityException if the delegator's model or entity-group configuration cannot be read
     */
    static Set<String> resolveComponentEntityGroups(String delegatorName, String componentName) throws GenericEntityException {
        ModelReader modelReader = ModelReader.getModelReader(delegatorName);
        ModelGroupReader groupReader = ModelGroupReader.getModelGroupReader(delegatorName);
        Set<String> entityNames = new HashSet<>();
        for (ComponentConfig.EntityResourceInfo resourceInfo : ComponentConfig.getAllEntityResourceInfos("model", componentName)) {
            ResourceHandler handler = resourceInfo.createResourceHandler();
            Collection<String> entities = modelReader.getResourceHandlerEntities(handler);
            if (entities != null) {
                entityNames.addAll(entities);
            }
        }
        Set<String> groupNames = new HashSet<>();
        for (String entityName : entityNames) {
            groupNames.add(groupReader.getEntityGroupName(entityName, delegatorName));
        }
        return groupNames;
    }

    /**
     * Builds the component-scoped Flyway schema-history table name. Every component migrates against
     * the same physical schema, so each needs its own history table.
     * @param componentName the OFBiz component name
     * @return the schema-history table name for that component
     */
    static String historyTableName(String componentName) {
        return "flyway_schema_history_" + componentName.replace('-', '_');
    }

    /**
     * Builds the vendor-specific migrations directory for a component.
     * @param componentRoot the component's root directory
     * @param vendor the active datasource's {@code field-type-name}
     * @return {@code <componentRoot>/migrations/<vendor>}
     */
    static Path migrationsDirectory(Path componentRoot, String vendor) {
        return componentRoot.resolve(MIGRATIONS_DIRECTORY_NAME).resolve(vendor);
    }

    /**
     * Reports whether a component ships any migrations at all, for any vendor.
     * @param componentRoot the component's root directory
     * @return {@code true} if {@code <componentRoot>/migrations} exists as a directory
     */
    static boolean hasAnyMigrationsDirectory(Path componentRoot) {
        return Files.isDirectory(componentRoot.resolve(MIGRATIONS_DIRECTORY_NAME));
    }

    /**
     * Locates a component's root directory by name. Uses the loaded component cache when running
     * inside a booted OFBiz; otherwise — the standalone CLI case, where no container has populated
     * that cache — walks the root component directories declared in {@code component-load.xml}.
     * @param componentName the OFBiz component name, e.g. {@code example}
     * @return the component's root directory
     * @throws GenericConfigException if no such component can be found
     */
    static Path resolveComponentRoot(String componentName) throws GenericConfigException {
        if (Boolean.TRUE.equals(ComponentConfig.componentExists(componentName))) {
            return ComponentConfig.getComponentConfig(componentName).rootLocation();
        }
        Path ofbizHome = Paths.get(System.getProperty("ofbiz.home", ".")).toAbsolutePath().normalize();
        for (ComponentLoaderConfig.ComponentDef def : ComponentLoaderConfig.getRootComponents()) {
            Path location = def.getLocation().isAbsolute() ? def.getLocation() : ofbizHome.resolve(def.getLocation());
            Path candidate = def.getType() == ComponentLoaderConfig.ComponentType.SINGLE_COMPONENT
                    ? location
                    : location.resolve(componentName);
            if (componentName.equals(candidate.getFileName().toString())
                    && Files.exists(candidate.resolve(ComponentConfig.OFBIZ_COMPONENT_XML_FILENAME))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new GenericConfigException("No component named '" + componentName + "' found under " + ofbizHome
                + "; check the name and that -Dofbiz.home points at your OFBiz installation");
    }
}
