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

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ofbiz.entity.jdbc.DatabaseUtil;

/**
 * Compares a live database's actual schema against a reference schema (typically a freshly
 * generated Flyway baseline target), to catch drift before a brownfield install is baselined.
 * Does not modify either database.
 */
public final class SchemaDriftAuditor {

    private final Connection liveSchema;
    private final Connection referenceSchema;

    public SchemaDriftAuditor(Connection liveSchema, Connection referenceSchema) {
        this.liveSchema = liveSchema;
        this.referenceSchema = referenceSchema;
    }

    public record DriftFinding(String tableName, String description) { }

    public List<DriftFinding> findDrift(List<String> tableNames) {
        List<DriftFinding> findings = new ArrayList<>();
        Map<String, Set<String>> liveColumnsByTable;
        Map<String, Set<String>> referenceColumnsByTable;
        try {
            liveColumnsByTable = columnsBySchema(liveSchema, null);
            referenceColumnsByTable = columnsBySchema(referenceSchema, null);
        } catch (SQLException e) {
            for (String tableName : tableNames) {
                findings.add(new DriftFinding(tableName, "failed to compare: " + e.getMessage()));
            }
            return findings;
        }
        for (String tableName : tableNames) {
            try {
                String liveKey = normalizedTableName(liveSchema, tableName);
                String referenceKey = normalizedTableName(referenceSchema, tableName);
                Set<String> liveColumns = liveColumnsByTable.getOrDefault(liveKey, Set.of());
                Set<String> referenceColumns = referenceColumnsByTable.getOrDefault(referenceKey, Set.of());

                Set<String> missingFromLive = new LinkedHashSet<>(referenceColumns);
                missingFromLive.removeAll(liveColumns);
                if (!missingFromLive.isEmpty()) {
                    findings.add(new DriftFinding(tableName,
                            "live schema is missing column(s) present in reference: " + missingFromLive));
                }

                Set<String> extraInLive = new LinkedHashSet<>(liveColumns);
                extraInLive.removeAll(referenceColumns);
                if (!extraInLive.isEmpty()) {
                    findings.add(new DriftFinding(tableName,
                            "live schema has extra column(s) not present in reference: " + extraInLive));
                }
            } catch (SQLException e) {
                findings.add(new DriftFinding(tableName, "failed to compare: " + e.getMessage()));
            }
        }
        return findings;
    }

    /**
     * Fetches every column of every table in {@code schemaName} (or unscoped, if {@code
     * schemaName} is null/blank) in one metadata call, keyed by each table's name normalized the
     * same way {@link DatabaseUtil} itself normalizes table names read back from the database —
     * via {@link DatabaseUtil.ColumnCheckInfo#fixupTableName} — so a table-name case difference
     * between what the database actually stores and what a caller asks for doesn't cause a missed
     * match. One metadata call regardless of how many tables are needed, rather than one call per
     * table.
     * @param schemaName the schema to scope the fetch to, or {@code null}/blank for unscoped
     * @return column names (uppercased) keyed by normalized table name
     */
    static Map<String, Set<String>> columnsBySchema(Connection conn, String schemaName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        boolean needsUpperCase = metaData.storesLowerCaseIdentifiers() || metaData.storesMixedCaseIdentifiers();
        String schemaPattern = schemaName == null || schemaName.isBlank() ? null : schemaName;
        Map<String, Set<String>> columnsByTable = new HashMap<>();
        try (ResultSet rs = metaData.getColumns(null, schemaPattern, null, null)) {
            while (rs.next()) {
                // fixupTableName's own "already schema-qualified?" guard is unreliable (it does a
                // literal backslash-dot string check, not a real prefix test), so it's used here
                // only for its case-folding half - lookupSchemaName is deliberately always null.
                // No prefix is needed anyway: this method already scopes its query to one schema
                // via schemaPattern, so its result map is never merged with another schema's, and
                // a bare (case-folded) table name is unambiguous within it.
                String normalizedTableName = DatabaseUtil.ColumnCheckInfo.fixupTableName(
                        rs.getString("TABLE_NAME"), null, needsUpperCase);
                columnsByTable.computeIfAbsent(normalizedTableName, key -> new TreeSet<>())
                        .add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        return columnsByTable;
    }

    /**
     * Normalizes a table name (e.g. from {@code ModelEntity.getPlainTableName()}) the same way
     * {@link #columnsBySchema} normalizes the names it reads back from the database, so a caller
     * can reliably look a specific table's columns up from {@link #columnsBySchema}'s result.
     */
    static String normalizedTableName(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        boolean needsUpperCase = metaData.storesLowerCaseIdentifiers() || metaData.storesMixedCaseIdentifiers();
        // Deliberately no schema prefix here either - see the comment in columnsBySchema for why
        // one isn't needed, and why relying on fixupTableName's own prefix-detection would be risky.
        return DatabaseUtil.ColumnCheckInfo.fixupTableName(tableName, null, needsUpperCase);
    }
}
