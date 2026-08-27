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
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityConfException;
import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.apache.ofbiz.entity.config.model.GroupMap;

/**
 * Standalone entry point for the {@code runMigrations} Gradle task: runs every Flyway-managed
 * component's pending migrations for a delegator, outside the application's own boot sequence.
 *
 * <p>Intended for {@code execution-mode=external} deployments: a deploy pipeline runs this task
 * as an explicit, observable pre-deploy step (with its own timeout, monitoring, and maintenance
 * window if needed) before the application is started at all, so a slow or risky migration on a
 * large table never happens during a live application restart. {@link MigrationContainer} then
 * only validates, at boot, that this step actually ran.</p>
 *
 * <p>Usage: {@code RunMigrations <delegatorName>}. Invoked by the Gradle task; do not call
 * directly.</p>
 */
public final class RunMigrations {

    private static final String MODULE = RunMigrations.class.getName();

    private RunMigrations() {
    }

    public static void main(String[] args) throws Exception {
        MigrationSupport.bootstrapComponentsIfNeeded();
        String delegatorName = args.length > 0 && !args[0].isBlank() ? args[0] : "default";

        List<ComponentConfig> components = ComponentConfig.components().toList();
        Debug.logInfo("[runMigrations] Resolved " + components.size() + " component(s) for delegator '"
                + delegatorName + "'", MODULE);
        DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
        if (delegator == null) {
            throw new GenericEntityConfException("No <delegator> named '" + delegatorName + "' found in entityengine.xml");
        }

        int migratedDatasources = 0;
        for (GroupMap groupMap : delegator.getGroupMapList()) {
            Datasource datasource = EntityConfig.getDatasource(groupMap.getDatasourceName());
            String strategyValue = datasource == null ? null : datasource.getSchemaManagementStrategy();
            if (!(MigrationContainer.resolveStrategy(strategyValue) instanceof FlywayStrategy strategy)) {
                continue;
            }
            MigrationSupport.JdbcTarget target =
                    MigrationSupport.resolveJdbcTarget(groupMap.getGroupName(), groupMap.getDatasourceName());
            if (target == null) {
                continue;
            }
            Debug.logInfo("[runMigrations] Running migrations for datasource '" + target.datasourceName()
                    + "' (group=" + target.groupName() + ", vendor=" + target.vendor() + ")", MODULE);
            strategy.apply(delegatorName, target, components);
            migratedDatasources++;
        }

        if (migratedDatasources == 0) {
            Debug.logWarning("[runMigrations] Done: processed 0 flyway-managed datasource(s) for delegator '"
                    + delegatorName + "' - if you expected migrations to run, check that schema-management-strategy="
                    + "\"flyway\" is set on the relevant <datasource> in entityengine.xml", MODULE);
        } else {
            Debug.logInfo("[runMigrations] Done: processed " + migratedDatasources
                    + " flyway-managed datasource(s) for delegator '" + delegatorName + "'.", MODULE);
        }
    }
}
