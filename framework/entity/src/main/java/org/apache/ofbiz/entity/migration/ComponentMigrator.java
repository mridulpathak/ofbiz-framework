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

import java.nio.file.Path;

import org.flywaydb.core.Flyway;

/**
 * Runs Flyway migrations for a single OFBiz component's {@code migrations/<vendor>/}
 * directory against one JDBC connection, tracked in a component-scoped schema-history table.
 */
public final class ComponentMigrator {

    private final String jdbcUrl;
    private final String jdbcUsername;
    private final String jdbcPassword;
    private final Path migrationsDirectory;
    private final String historyTableName;
    private final Flyway flyway;

    public ComponentMigrator(String jdbcUrl, String jdbcUsername, String jdbcPassword,
            Path migrationsDirectory, String historyTableName) {
        this.jdbcUrl = jdbcUrl;
        this.jdbcUsername = jdbcUsername;
        this.jdbcPassword = jdbcPassword;
        this.migrationsDirectory = migrationsDirectory;
        this.historyTableName = historyTableName;
        // In OFBiz's shared-schema architecture, every component migrates against the same
        // physical database, so by the time this component's migrate() first runs, other
        // components' tables (from auto-DDL or their own migrations) will typically already
        // exist even though this component has no Flyway schema-history table yet. Without
        // baselineOnMigrate, Flyway treats that as an unexpected non-empty schema and refuses
        // to migrate. baselineOnMigrate(true) + baselineVersion("0") tells Flyway to silently
        // baseline at version "0" (a no-op, since no V0 migration exists) in that situation,
        // so this component's real migrations (V1+) still run normally against its own,
        // as-yet-unmigrated tables. This is scoped to the migrate() path only; baseline()
        // below keeps building its own configuration with its caller-supplied version.
        // Accepted trade-off: this removes Flyway's normal safety net against pointing
        // migrate() at the wrong (non-empty) database by mistake -- a misconfigured jdbcUrl
        // would now baseline-and-migrate silently instead of refusing. Deliberate given
        // OFBiz's single shared schema, where "non-empty, no history yet" is the norm.
        this.flyway = baseConfiguration()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
    }

    public record MigrateResult(int migrationsExecuted) { }

    public MigrateResult migrate() {
        org.flywaydb.core.api.output.MigrateResult flywayResult = flyway.migrate();
        return new MigrateResult(flywayResult.migrationsExecuted);
    }

    /** Reports whether this component has migration files that have not yet been applied. */
    public boolean hasPendingMigrations() {
        return flyway.info().pending().length > 0;
    }

    /** Marks the schema-history table as already applied through {@code baselineVersion}, without running any SQL. */
    public void baseline(String baselineVersion, String baselineDescription) {
        baseConfiguration()
                .baselineVersion(baselineVersion)
                .baselineDescription(baselineDescription)
                .load()
                .baseline();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration baseConfiguration() {
        return Flyway.configure()
                .dataSource(jdbcUrl, jdbcUsername, jdbcPassword)
                .locations("filesystem:" + migrationsDirectory.toAbsolutePath())
                .table(historyTableName);
    }
}
