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

import org.apache.ofbiz.base.util.Debug;

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

        Path componentRoot = MigrationSupport.resolveComponentRoot(componentName);
        List<MigrationSupport.JdbcTarget> targets = MigrationSupport.resolveJdbcTargets(delegatorName);
        String historyTable = MigrationSupport.historyTableName(componentName);
        int baselined = 0;
        for (MigrationSupport.JdbcTarget target : targets) {
            Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, target.vendor());
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
