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
import java.sql.SQLException;
import java.util.Set;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityConfException;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.apache.ofbiz.entity.config.model.GroupMap;

/**
 * Standalone entry point for the {@code baselineMigration} Gradle task: adopts an existing
 * ("brownfield") install into Flyway by writing a BASELINE row into one component's schema-history
 * table, without running any of that component's migration SQL.
 *
 * <p>Deliberately separate from {@link MigrationContainer} and never run at boot. Baselining
 * asserts that the live schema already matches the component's baseline migration, which only a
 * human-reviewed drift audit ({@link SchemaDriftAuditor}) can establish; auto-baselining at
 * startup would silently paper over exactly the drift that audit exists to catch.</p>
 *
 * <p>Usage: {@code BaselineComponentMigration <delegatorName> <componentName> <baselineVersion>
 * [baselineDescription]}. Invoked by the Gradle task; do not call directly.</p>
 */
public final class BaselineComponentMigration {

    private static final String MODULE = BaselineComponentMigration.class.getName();
    private static final String USAGE =
            "Usage: BaselineComponentMigration <delegatorName> <componentName> <baselineVersion> [baselineDescription]";

    private BaselineComponentMigration() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            Debug.logError(USAGE, MODULE);
            System.exit(1);
        }
        String delegatorName = blankToDefault(args[0], "default");
        String componentName = args[1];
        String baselineVersion = args[2];
        String baselineDescription = blankToDefault(args.length > 3 ? args[3] : null, "baseline");
        if (componentName == null || componentName.isBlank() || baselineVersion == null || baselineVersion.isBlank()) {
            Debug.logError("componentName and baselineVersion are both required. " + USAGE, MODULE);
            System.exit(1);
        }

        Debug.logWarning("[baselineMigration] Baselining marks a component's migrations as already applied WITHOUT"
                + " running them. Only do this after a SchemaDriftAuditor run has confirmed the live schema already"
                + " matches this baseline.", MODULE);

        MigrationSupport.bootstrapComponentsIfNeeded();

        Path componentRoot = MigrationSupport.resolveComponentRoot(componentName);
        String historyTable = MigrationSupport.historyTableName(componentName);

        Set<String> componentGroups;
        try {
            componentGroups = MigrationSupport.resolveComponentEntityGroups(delegatorName, componentName);
        } catch (GenericEntityException e) {
            Debug.logError(e, "[baselineMigration] Could not resolve entity groups for component '" + componentName + "'",
                    MODULE);
            System.exit(1);
            return;
        }
        componentGroups.remove(null);
        if (componentGroups.isEmpty() && MigrationSupport.hasAnyMigrationsDirectory(componentRoot, componentName)) {
            Debug.logError("[baselineMigration] Component '" + componentName + "' ships migrations but no entity group"
                    + " could be resolved for it (it declares no <entity-resource type=\"model\">); refusing to baseline"
                    + " it blindly", MODULE);
            System.exit(1);
            return;
        }

        DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
        if (delegator == null) {
            throw new GenericEntityConfException("No <delegator> named '" + delegatorName + "' found in entityengine.xml");
        }

        int baselined = 0;
        for (GroupMap groupMap : delegator.getGroupMapList()) {
            // Cheap, credential-free check first: only resolve real JDBC info (including password
            // decryption) for datasources whose strategy actually resolves to Flyway. A datasource
            // left at the default "auto-ddl" must never trigger credential resolution here.
            Datasource datasource = EntityConfig.getDatasource(groupMap.getDatasourceName());
            String strategyValue = datasource == null ? null : datasource.getSchemaManagementStrategy();
            if (!(MigrationContainer.resolveStrategy(strategyValue) instanceof FlywayStrategy)) {
                Debug.logInfo("[baselineMigration] Skipping datasource '" + groupMap.getDatasourceName()
                        + "': schema-management-strategy is not 'flyway'", MODULE);
                continue;
            }
            if (!componentGroups.contains(groupMap.getGroupName())) {
                Debug.logInfo("[baselineMigration] Skipping datasource '" + groupMap.getDatasourceName() + "': component '"
                        + componentName + "'s entities do not belong to group '" + groupMap.getGroupName() + "'", MODULE);
                continue;
            }
            MigrationSupport.JdbcTarget target =
                    MigrationSupport.resolveJdbcTarget(groupMap.getGroupName(), groupMap.getDatasourceName());
            if (target == null) {
                continue;
            }
            Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, componentName, target.vendor());
            if (!Files.isDirectory(migrationsDir)) {
                Debug.logInfo("[baselineMigration] Skipping datasource '" + target.datasourceName() + "': component '"
                        + componentName + "' has no migrations for vendor '" + target.vendor() + "' (" + migrationsDir + ")", MODULE);
                continue;
            }
            Debug.logInfo("[baselineMigration] Baselining component '" + componentName + "' at version " + baselineVersion
                    + " on datasource '" + target.datasourceName() + "' (vendor=" + target.vendor() + ", history table "
                    + historyTable + ")", MODULE);
            new ComponentMigrator(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword(), migrationsDir, historyTable)
                    .baseline(baselineVersion, baselineDescription);
            try (Connection conn = DriverManager.getConnection(target.jdbcUrl(), target.jdbcUsername(), target.jdbcPassword())) {
                Set<String> tableNames = SchemaFingerprint.resolveComponentTableNames(delegatorName, componentName);
                SchemaFingerprint.store(conn, componentName, SchemaFingerprint.compute(conn, target.schemaName(), tableNames));
            } catch (SQLException | GenericEntityException e) {
                Debug.logError(e, "[baselineMigration] Baselined component '" + componentName
                        + "' but could not record its schema fingerprint - the next migrate() attempt will not be able"
                        + " to detect drift until this is resolved", MODULE);
                System.exit(1);
                return;
            }
            baselined++;
        }

        if (baselined == 0) {
            Debug.logError("[baselineMigration] Nothing baselined: none of delegator '" + delegatorName + "'s datasources"
                    + " has a vendor matching a migrations/<vendor>/ directory under " + componentRoot, MODULE);
            System.exit(1);
        }
        Debug.logInfo("[baselineMigration] Done: baselined component '" + componentName + "' on "
                + baselined + " datasource(s).", MODULE);
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
