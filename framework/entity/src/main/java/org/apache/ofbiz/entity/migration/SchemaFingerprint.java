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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ofbiz.base.component.ComponentConfig;
import org.apache.ofbiz.base.config.ResourceHandler;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.model.ModelEntity;
import org.apache.ofbiz.entity.model.ModelReader;
import org.apache.ofbiz.entity.model.ModelViewEntity;

/**
 * Detects whether a component's live schema has drifted since Flyway last recorded its state —
 * e.g. auto-DDL altered it while this datasource's schema-management-strategy was "auto-ddl"
 * rather than "flyway". A fingerprint is a deterministic hash of a component's tables' column
 * structure, stored in a small dedicated table this class owns.
 */
final class SchemaFingerprint {

    private static final String FINGERPRINT_TABLE = "flyway_schema_fingerprint";
    private static final String HASH_ALGORITHM = "SHA-256";

    private SchemaFingerprint() {
    }

    /**
     * Resolves the real table name of every entity a component declares, via the same Entity
     * Engine machinery ({@code DatabaseUtil}/auto-DDL) uses to generate table names — so this
     * fingerprint's table list matches reality exactly, rather than re-deriving it from raw SQL.
     * @param delegatorName the {@code <delegator>} name from {@code entityengine.xml}
     * @param componentName the OFBiz component name
     * @return the component's table names, sorted for a deterministic fingerprint
     * @throws GenericEntityException if the delegator's entity model cannot be read
     */
    static Set<String> resolveComponentTableNames(String delegatorName, String componentName) throws GenericEntityException {
        ModelReader modelReader = ModelReader.getModelReader(delegatorName);
        Set<String> entityNames = new HashSet<>();
        for (ComponentConfig.EntityResourceInfo resourceInfo : ComponentConfig.getAllEntityResourceInfos("model", componentName)) {
            ResourceHandler handler = resourceInfo.createResourceHandler();
            Collection<String> entities = modelReader.getResourceHandlerEntities(handler);
            if (entities != null) {
                entityNames.addAll(entities);
            }
        }
        Set<String> tableNames = new TreeSet<>();
        for (String entityName : entityNames) {
            ModelEntity modelEntity = modelReader.getModelEntity(entityName);
            // View entities have no table of their own - getPlainTableName() returns null for
            // them, so they're excluded here the same way GenericDAO/SqlJdbcUtil exclude them
            // from DDL and query generation.
            if (!(modelEntity instanceof ModelViewEntity)) {
                tableNames.add(modelEntity.getPlainTableName());
            }
        }
        return tableNames;
    }

    /**
     * Computes a deterministic fingerprint of the given tables' current column structure.
     * @param conn a live JDBC connection to the schema to inspect
     * @param tableNames the tables to fingerprint, in the order to fingerprint them (pass a sorted
     *      set, e.g. from {@link #resolveComponentTableNames}, for a stable result across calls)
     * @return a hex-encoded SHA-256 hash of the tables' column signatures (name, type, size,
     *      precision, nullability, and MySQL charset/collation when applicable)
     * @throws SQLException if the schema cannot be inspected
     */
    static String compute(Connection conn, Set<String> tableNames) throws SQLException {
        return compute(conn, null, tableNames);
    }

    /**
     * Same as {@link #compute(Connection, Set)}, but scoped to a specific schema when computing
     * each table's columns — see {@link SchemaDriftAuditor#columnsBySchema(Connection, String)}.
     * @param schemaName the schema to scope table introspection to, or {@code null}/blank for the
     *      connection's default (unscoped) behavior
     */
    static String compute(Connection conn, String schemaName, Set<String> tableNames) throws SQLException {
        Map<String, Set<SchemaDriftAuditor.ColumnSignature>> columnsBySchema =
                SchemaDriftAuditor.columnsBySchema(conn, schemaName);
        StringBuilder canonical = new StringBuilder();
        for (String tableName : tableNames) {
            String normalizedName = SchemaDriftAuditor.normalizedTableName(conn, tableName);
            // ColumnSignature has no natural ordering the way String does via TreeSet's default
            // comparator, so sort explicitly by column name (matching the previous sort-by-name
            // behavior) to keep the resulting hash deterministic across calls. Chain a tiebreaker
            // over every remaining field for the case of two signatures sharing a columnName (which
            // hasDuplicateColumnNames can genuinely flag as ambiguous, e.g. columnsBySchema merging
            // same-named tables from different MySQL databases) - without a FULLY exhaustive
            // tiebreaker, the sort would fall back to List.sort's stability over the ResultSet's
            // unspecified row order for any two signatures still tied after it, which is not itself
            // guaranteed deterministic across calls.
            List<SchemaDriftAuditor.ColumnSignature> columns = new ArrayList<>(
                    columnsBySchema.getOrDefault(normalizedName, Set.of()));
            columns.sort(Comparator.comparing(SchemaDriftAuditor.ColumnSignature::columnName)
                    .thenComparing(SchemaDriftAuditor.ColumnSignature::typeName)
                    .thenComparingInt(SchemaDriftAuditor.ColumnSignature::columnSize)
                    .thenComparingInt(SchemaDriftAuditor.ColumnSignature::decimalDigits)
                    .thenComparing(SchemaDriftAuditor.ColumnSignature::nullable)
                    .thenComparing(SchemaDriftAuditor.ColumnSignature::charsetAndCollation));
            canonical.append(tableName).append('=');
            for (SchemaDriftAuditor.ColumnSignature column : columns) {
                canonical.append(column.columnName()).append(':')
                        .append(column.typeName()).append(':')
                        .append(column.columnSize()).append(':')
                        .append(column.decimalDigits()).append(':')
                        .append(column.nullable()).append(':')
                        .append(column.charsetAndCollation()).append(',');
            }
            canonical.append(';');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] hash = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HASH_ALGORITHM + " is not available on this JVM", e);
        }
    }

    /**
     * Stores (inserting or overwriting) a component's fingerprint.
     * @param conn a live JDBC connection with write access to the schema
     * @param componentName the OFBiz component name
     * @param fingerprint the fingerprint to store, e.g. from {@link #compute}
     * @throws SQLException if the fingerprint table cannot be created or written
     */
    static void store(Connection conn, String componentName, String fingerprint) throws SQLException {
        ensureTableExists(conn);
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + FINGERPRINT_TABLE + " SET fingerprint = ? WHERE component_name = ?")) {
            update.setString(1, fingerprint);
            update.setString(2, componentName);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO " + FINGERPRINT_TABLE + " (component_name, fingerprint) VALUES (?, ?)")) {
                    insert.setString(1, componentName);
                    insert.setString(2, fingerprint);
                    insert.executeUpdate();
                }
            }
        }
    }

    /**
     * Loads a component's previously stored fingerprint.
     * @param conn a live JDBC connection to the schema
     * @param componentName the OFBiz component name
     * @return the stored fingerprint, or {@code null} if none has ever been stored for this component
     * @throws SQLException if the fingerprint table cannot be created or read
     */
    static String load(Connection conn, String componentName) throws SQLException {
        ensureTableExists(conn);
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT fingerprint FROM " + FINGERPRINT_TABLE + " WHERE component_name = ?")) {
            select.setString(1, componentName);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString("fingerprint") : null;
            }
        }
    }

    private static void ensureTableExists(Connection conn) throws SQLException {
        try (Statement create = conn.createStatement()) {
            create.execute("CREATE TABLE IF NOT EXISTS " + FINGERPRINT_TABLE
                    + " (component_name VARCHAR(255) PRIMARY KEY, fingerprint VARCHAR(64) NOT NULL)");
        }
    }
}
