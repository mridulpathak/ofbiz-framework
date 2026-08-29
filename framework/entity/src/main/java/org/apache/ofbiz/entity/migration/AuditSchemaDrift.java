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
import java.util.List;
import java.util.Set;

import org.apache.ofbiz.base.container.ContainerException;
import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.GenericEntityConfException;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.config.model.Datasource;
import org.apache.ofbiz.entity.config.model.DelegatorElement;
import org.apache.ofbiz.entity.config.model.EntityConfig;
import org.apache.ofbiz.entity.config.model.GroupMap;

/**
 * Standalone entry point for the {@code auditSchemaDrift} Gradle task: before baselining a
 * brownfield component, proves whether its live (existing) schema genuinely matches what running
 * its real migrations from scratch would produce.
 *
 * <p>Runs the component's real migrations for the resolved vendor into an operator-supplied,
 * empty, same-vendor scratch database, then compares the result against the component's real,
 * {@code entityengine.xml}-configured live datasource via {@link SchemaDriftAuditor}. The operator
 * is responsible only for provisioning an empty scratch database of the right vendor - this CLI
 * does the part that's actually error-prone (running the migrations correctly).</p>
 *
 * <p>Usage: {@code AuditSchemaDrift <delegatorName> <componentName> <scratchJdbcUrl>
 * <scratchJdbcUsername> <scratchJdbcPassword>}. Invoked by the Gradle task; do not call directly.</p>
 */
public final class AuditSchemaDrift {

    private static final String MODULE = AuditSchemaDrift.class.getName();
    private static final String USAGE = "Usage: AuditSchemaDrift <delegatorName> <componentName> <scratchJdbcUrl> "
            + "<scratchJdbcUsername> <scratchJdbcPassword>";

    private AuditSchemaDrift() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            Debug.logError(USAGE, MODULE);
            System.exit(1);
            return;
        }
        String delegatorName = blankToDefault(args[0], "default");
        String componentName = args[1];
        String scratchJdbcUrl = args[2];
        String scratchJdbcUsername = args[3];
        String scratchJdbcPassword = args[4];
        if (componentName == null || componentName.isBlank()) {
            Debug.logError("componentName is required. " + USAGE, MODULE);
            System.exit(1);
            return;
        }

        MigrationSupport.bootstrapComponentsIfNeeded();

        Path componentRoot = MigrationSupport.resolveComponentRoot(componentName);

        Set<String> componentGroups;
        try {
            componentGroups = MigrationSupport.resolveComponentEntityGroups(delegatorName, componentName);
        } catch (GenericEntityException e) {
            Debug.logError(e, "[auditSchemaDrift] Could not resolve entity groups for component '" + componentName + "'", MODULE);
            System.exit(1);
            return;
        }
        componentGroups.remove(null);
        if (componentGroups.isEmpty()) {
            Debug.logError("[auditSchemaDrift] Component '" + componentName
                    + "' resolves no entity group; cannot determine which datasource its live schema lives on", MODULE);
            System.exit(1);
            return;
        }

        DelegatorElement delegator = EntityConfig.getInstance().getDelegator(delegatorName);
        if (delegator == null) {
            throw new GenericEntityConfException("No <delegator> named '" + delegatorName + "' found in entityengine.xml");
        }

        // First pass: resolve every candidate live JDBC target for this component across all
        // Flyway-managed group maps before any migration work begins. This lets the
        // scratch/live collision guard below check scratchJdbcUrl against the FULL set of live
        // datasources up front - if it only checked one target per loop iteration, a component
        // spanning multiple Flyway-managed datasources could have its first datasource migrated
        // against scratchJdbcUrl before the loop ever reached the iteration whose guard would
        // have caught a collision with a later datasource.
        List<MigrationSupport.JdbcTarget> liveTargets =
                resolveFlywayManagedLiveTargets(delegator, componentGroups, componentName);

        for (MigrationSupport.JdbcTarget liveTarget : liveTargets) {
            if (scratchJdbcUrl.equals(liveTarget.jdbcUrl())) {
                Debug.logError("[auditSchemaDrift] scratchJdbcUrl must not be the same as the live JDBC URL ('"
                        + liveTarget.jdbcUrl() + "' for datasource '" + liveTarget.datasourceName() + "') - this tool "
                        + "would otherwise run migrations directly against the live database instead of a disposable "
                        + "scratch database. Double-check the -PscratchJdbcUrl value and point it at an empty, "
                        + "throwaway database instead", MODULE);
                System.exit(1);
                return;
            }
        }

        int audited = 0;
        for (MigrationSupport.JdbcTarget liveTarget : liveTargets) {
            Path migrationsDir = MigrationSupport.migrationsDirectory(componentRoot, componentName, liveTarget.vendor());
            if (!Files.isDirectory(migrationsDir)) {
                Debug.logInfo("[auditSchemaDrift] Skipping datasource '" + liveTarget.datasourceName() + "': component '"
                        + componentName + "' has no migrations for vendor '" + liveTarget.vendor() + "' (" + migrationsDir + ")",
                        MODULE);
                continue;
            }
            Debug.logInfo("[auditSchemaDrift] Migrating the scratch database with component '" + componentName
                    + "'s real migrations (vendor=" + liveTarget.vendor() + ") to build the reference schema", MODULE);
            String historyTable = MigrationSupport.historyTableName(componentName);
            new ComponentMigrator(scratchJdbcUrl, scratchJdbcUsername, scratchJdbcPassword, migrationsDir, historyTable)
                    .migrate();

            Set<String> tableNames = SchemaFingerprint.resolveComponentTableNames(delegatorName, componentName);
            try (Connection liveConn = DriverManager.getConnection(liveTarget.jdbcUrl(), liveTarget.jdbcUsername(),
                    liveTarget.jdbcPassword());
                    Connection scratchConn = DriverManager.getConnection(scratchJdbcUrl, scratchJdbcUsername, scratchJdbcPassword)) {
                // Both sides are resolved from the same liveTarget's OFBiz-configured schema-name
                // convention, applied against each connection's own metadata - correct here because
                // the live and scratch databases are the same vendor and represent "the same
                // datasource slot," just two different physical databases being compared. This
                // genuinely helps on a vendor with real per-connection schemas, such as Postgres or
                // Oracle, where an unscoped comparison could otherwise see tables from more than one
                // schema. It does NOT help on MySQL: MigrationSupport.resolveSchemaName resolves to
                // null there regardless (MySQL doesn't support schemas in table definitions the way
                // that method checks for), so the MySQL cross-database column-merge hazard documented
                // in SchemaDriftAuditor.enrichWithMySqlCharsetAndCollation's javadoc remains a
                // separate, pre-existing limitation unaffected by this schema-name resolution - see
                // SchemaDriftAuditor.findDrift(List, String, String)'s javadoc for the full story.
                String liveSchemaName = MigrationSupport.resolveSchemaName(liveTarget, liveConn);
                String scratchSchemaName = MigrationSupport.resolveSchemaName(liveTarget, scratchConn);
                SchemaDriftAuditor auditor = new SchemaDriftAuditor(liveConn, scratchConn);
                List<SchemaDriftAuditor.DriftFinding> findings =
                        auditor.findDrift(List.copyOf(tableNames), liveSchemaName, scratchSchemaName);
                if (findings.isEmpty()) {
                    Debug.logInfo("[auditSchemaDrift] No drift found for component '" + componentName + "' on datasource '"
                            + liveTarget.datasourceName() + "' - safe to baseline", MODULE);
                } else {
                    Debug.logError("[auditSchemaDrift] Drift found for component '" + componentName + "' on datasource '"
                            + liveTarget.datasourceName() + "' - NOT safe to baseline until reconciled:", MODULE);
                    for (SchemaDriftAuditor.DriftFinding finding : findings) {
                        Debug.logError("[auditSchemaDrift]   " + finding.tableName() + ": " + finding.description(), MODULE);
                    }
                    System.exit(1);
                    return;
                }
            }
            audited++;
        }

        if (audited == 0) {
            Debug.logWarning("[auditSchemaDrift] Done: audited 0 flyway-managed datasource(s) for component '" + componentName
                    + "' - if you expected an audit to run, check schema-management-strategy=\"flyway\" is set and that "
                    + componentName + " has migrations for the target vendor", MODULE);
        } else {
            Debug.logInfo("[auditSchemaDrift] Done: audited " + audited + " datasource(s) for component '" + componentName
                    + "'.", MODULE);
        }
    }

    /**
     * Resolves the live {@link MigrationSupport.JdbcTarget} for every group map in {@code delegator}
     * that both belongs to one of {@code componentGroups} and is backed by a datasource whose
     * schema-management-strategy resolves to Flyway. A group map failing either check is silently
     * skipped (and logged), exactly as before this method was extracted - callers must not
     * duplicate this qualification logic, so that the two passes over the group map list (the
     * scratch/live collision check and the actual migrate-and-audit work) can never drift out of
     * sync on which datasources qualify.
     */
    private static List<MigrationSupport.JdbcTarget> resolveFlywayManagedLiveTargets(DelegatorElement delegator,
            Set<String> componentGroups, String componentName) throws ContainerException, GenericEntityConfException {
        List<MigrationSupport.JdbcTarget> liveTargets = new ArrayList<>();
        for (GroupMap groupMap : delegator.getGroupMapList()) {
            // Cheap, credential-free check first: only resolve real JDBC info (including password
            // decryption) for datasources whose strategy actually resolves to Flyway. A datasource
            // left at the default "auto-ddl" must never trigger credential resolution here.
            Datasource datasource = EntityConfig.getDatasource(groupMap.getDatasourceName());
            String strategyValue = datasource == null ? null : datasource.getSchemaManagementStrategy();
            if (!(MigrationContainer.resolveStrategy(strategyValue) instanceof FlywayStrategy)) {
                Debug.logInfo("[auditSchemaDrift] Skipping datasource '" + groupMap.getDatasourceName()
                        + "': schema-management-strategy is not 'flyway'", MODULE);
                continue;
            }
            if (!componentGroups.contains(groupMap.getGroupName())) {
                Debug.logInfo("[auditSchemaDrift] Skipping datasource '" + groupMap.getDatasourceName() + "': component '"
                        + componentName + "'s entities do not belong to group '" + groupMap.getGroupName() + "'", MODULE);
                continue;
            }
            MigrationSupport.JdbcTarget liveTarget =
                    MigrationSupport.resolveJdbcTarget(groupMap.getGroupName(), groupMap.getDatasourceName());
            if (liveTarget == null) {
                continue;
            }
            liveTargets.add(liveTarget);
        }
        return liveTargets;
    }

    private static String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
