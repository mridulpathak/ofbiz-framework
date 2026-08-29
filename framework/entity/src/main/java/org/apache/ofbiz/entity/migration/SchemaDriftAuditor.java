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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ofbiz.base.util.Debug;
import org.apache.ofbiz.entity.jdbc.DatabaseUtil;

/**
 * Compares a live database's actual schema against a reference schema (typically a freshly
 * generated Flyway baseline target), to catch drift before a brownfield install is baselined.
 * Does not modify either database.
 */
public final class SchemaDriftAuditor {

    private static final String MODULE = SchemaDriftAuditor.class.getName();

    /**
     * Maximum number of ambiguous table names listed by name in {@link #logAmbiguousTablesWarning}'s
     * aggregated warning before the remainder are collapsed into a "+N more" suffix, so the
     * aggregated message itself cannot grow unbounded on a server hosting many colliding databases.
     */
    private static final int MAX_AMBIGUOUS_TABLES_LOGGED = 20;

    private final Connection liveSchema;
    private final Connection referenceSchema;

    public SchemaDriftAuditor(Connection liveSchema, Connection referenceSchema) {
        this.liveSchema = liveSchema;
        this.referenceSchema = referenceSchema;
    }

    public record DriftFinding(String tableName, String description) { }

    /**
     * One column's full comparable signature — not just its name, so a type, size, precision, or
     * nullability change registers as drift, not only a column being added or removed.
     * {@code charsetAndCollation} is blank except when populated by MySQL-specific enrichment
     * (H2/Postgres have no meaningful per-column equivalent); two signatures with different
     * {@code charsetAndCollation} values (one populated, one blank) are therefore genuinely
     * "different" by this record's natural equality, which is intentional in a mixed-vendor
     * comparison but never actually occurs in practice, since both sides of any real comparison
     * go through the identical enrichment step for the same reason.
     */
    record ColumnSignature(String columnName, String typeName, int columnSize, int decimalDigits, boolean nullable,
            String charsetAndCollation) {

        static ColumnSignature withoutCharset(String columnName, String typeName, int columnSize, int decimalDigits,
                boolean nullable) {
            return new ColumnSignature(columnName, typeName, columnSize, decimalDigits, nullable, "");
        }
    }

    /**
     * Delegates to {@link #findDrift(List, String, String)} with no schema scoping on either side -
     * preserved for callers (and tests) that compare two connections each already dedicated to a
     * single schema/database, where unscoped introspection is unambiguous.
     */
    public List<DriftFinding> findDrift(List<String> tableNames) {
        return findDrift(tableNames, null, null);
    }

    /**
     * Compares {@code tableNames} between {@link #liveSchema} and {@link #referenceSchema}, scoping
     * each side's column introspection to its own schema name. Schema-scoping matters on a database
     * server that hosts more than one real schema per connection - e.g. Postgres or Oracle, where an
     * unscoped {@code DatabaseMetaData.getColumns} call can return columns for every schema visible
     * to the connection, not just the one this comparison actually cares about - so passing {@code
     * null} here on such a server risks comparing the wrong tables entirely and reporting a false
     * "no drift" result.
     *
     * <p><strong>This does not help on MySQL.</strong> MySQL treats databases as JDBC catalogs, not
     * schemas - {@code DatabaseMetaData.supportsSchemasInTableDefinitions()} is {@code false} for
     * MySQL Connector/J, so {@code MigrationSupport.resolveSchemaName} (via {@code
     * DatabaseUtil.getSchemaName}) always resolves to {@code null} there regardless of what a caller
     * passes in, and even a non-null value would only ever be used as {@code getColumns}'s SCHEMA
     * pattern, not its CATALOG pattern. The MySQL cross-database column-name-merge hazard this class
     * also has to guard against (see {@link #enrichWithMySqlCharsetAndCollation}'s javadoc) is a
     * catalog-level phenomenon, so it remains a separate, pre-existing limitation unaffected by this
     * overload.
     * @param liveSchemaName the schema to scope {@link #liveSchema}'s introspection to, or {@code
     *      null}/blank for unscoped
     * @param referenceSchemaName the schema to scope {@link #referenceSchema}'s introspection to, or
     *      {@code null}/blank for unscoped
     */
    public List<DriftFinding> findDrift(List<String> tableNames, String liveSchemaName, String referenceSchemaName) {
        List<DriftFinding> findings = new ArrayList<>();
        Map<String, Set<ColumnSignature>> liveColumnsByTable;
        Map<String, Set<ColumnSignature>> referenceColumnsByTable;
        try {
            liveColumnsByTable = columnsBySchema(liveSchema, liveSchemaName);
            referenceColumnsByTable = columnsBySchema(referenceSchema, referenceSchemaName);
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
                Set<ColumnSignature> liveColumns = liveColumnsByTable.getOrDefault(liveKey, Set.of());
                Set<ColumnSignature> referenceColumns = referenceColumnsByTable.getOrDefault(referenceKey, Set.of());

                if (hasDuplicateColumnNames(liveColumns) || hasDuplicateColumnNames(referenceColumns)) {
                    // Either side's column set for this table is ambiguous - two ColumnSignatures
                    // sharing a columnName() (most likely columnsBySchema merging same-named tables
                    // from more than one MySQL database, see enrichWithMySqlCharsetAndCollation's
                    // javadoc). The name-keyed changed-column reclassification below relies on a
                    // column name uniquely identifying one signature per side, and a plain
                    // Map.put() would silently drop one of two same-named entries - potentially
                    // masking a genuine difference as "no drift" if the surviving entry happens to
                    // match the other side. Fall back to a plain set difference instead, which can
                    // only over-report (e.g. describing a changed column as a missing-plus-extra
                    // pair rather than one "changed" finding), never silently drop a real
                    // difference.
                    Debug.logWarning("Table " + tableName + " has more than one column reported under the same "
                            + "name on its live and/or reference side (likely columnsBySchema merging same-named "
                            + "tables from more than one schema or database) - falling back to a plain "
                            + "column-name set difference for this table instead of the usual, more precise "
                            + "missing/extra/changed comparison. Any findings below for this table may describe a "
                            + "changed column as a missing-plus-extra pair rather than one precise 'changed' "
                            + "finding.", MODULE);
                    Set<ColumnSignature> missing = new LinkedHashSet<>(referenceColumns);
                    missing.removeAll(liveColumns);
                    if (!missing.isEmpty()) {
                        findings.add(new DriftFinding(tableName,
                                "live schema is missing column(s) present in reference: " + missing));
                    }
                    Set<ColumnSignature> extra = new LinkedHashSet<>(liveColumns);
                    extra.removeAll(referenceColumns);
                    if (!extra.isEmpty()) {
                        findings.add(new DriftFinding(tableName,
                                "live schema has extra column(s) not present in reference: " + extra));
                    }
                    continue;
                }

                // Partition by column name first, so a column that merely changed (type, size,
                // precision, nullability, or charset) is reported once as a distinct "changed"
                // finding rather than as one "missing" finding and one unrelated "extra" finding -
                // which would misdescribe a change as an add-plus-remove pair. Safe here only
                // because the duplicate-name check above already ruled out either side having two
                // signatures share a name for this table.
                Map<String, ColumnSignature> liveByName = new LinkedHashMap<>();
                for (ColumnSignature column : liveColumns) {
                    liveByName.put(column.columnName(), column);
                }
                Map<String, ColumnSignature> referenceByName = new LinkedHashMap<>();
                for (ColumnSignature column : referenceColumns) {
                    referenceByName.put(column.columnName(), column);
                }

                Set<ColumnSignature> missingFromLive = new LinkedHashSet<>();
                List<String> changedDescriptions = new ArrayList<>();
                for (Map.Entry<String, ColumnSignature> entry : referenceByName.entrySet()) {
                    ColumnSignature liveColumn = liveByName.get(entry.getKey());
                    if (liveColumn == null) {
                        missingFromLive.add(entry.getValue());
                    } else if (!liveColumn.equals(entry.getValue())) {
                        changedDescriptions.add("column " + entry.getKey() + " differs: reference="
                                + entry.getValue() + " live=" + liveColumn);
                    }
                }
                if (!missingFromLive.isEmpty()) {
                    findings.add(new DriftFinding(tableName,
                            "live schema is missing column(s) present in reference: " + missingFromLive));
                }

                Set<ColumnSignature> extraInLive = new LinkedHashSet<>();
                for (Map.Entry<String, ColumnSignature> entry : liveByName.entrySet()) {
                    if (!referenceByName.containsKey(entry.getKey())) {
                        extraInLive.add(entry.getValue());
                    }
                }
                if (!extraInLive.isEmpty()) {
                    findings.add(new DriftFinding(tableName,
                            "live schema has extra column(s) not present in reference: " + extraInLive));
                }

                for (String changedDescription : changedDescriptions) {
                    findings.add(new DriftFinding(tableName, changedDescription));
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
     * @return each column's full signature keyed by normalized table name
     */
    static Map<String, Set<ColumnSignature>> columnsBySchema(Connection conn, String schemaName) throws SQLException {
        DatabaseMetaData metaData = conn.getMetaData();
        boolean needsUpperCase = metaData.storesLowerCaseIdentifiers() || metaData.storesMixedCaseIdentifiers();
        String schemaPattern = schemaName == null || schemaName.isBlank() ? null : schemaName;
        Map<String, Set<ColumnSignature>> columnsByTable = new HashMap<>();
        try (ResultSet rs = metaData.getColumns(null, schemaPattern, null, null)) {
            while (rs.next()) {
                // fixupTableName's own "already schema-qualified?" guard is unreliable (it does a
                // literal backslash-dot string check, not a real prefix test), so it's used here
                // only for its case-folding half - lookupSchemaName is deliberately always null.
                // A prefix is unnecessary when schemaPattern actually scopes this query to one
                // schema: the result map then can't merge another schema's tables in, so a bare
                // (case-folded) table name is unambiguous within it. But schemaPattern can be null
                // here - via findDrift's no-schema overload, SchemaFingerprint.compute's unscoped
                // 2-arg overload, or always on MySQL, where schema resolution never yields a
                // non-null value (see enrichWithMySqlCharsetAndCollation's javadoc) - and then this
                // map genuinely can merge same-named tables/columns from different schemas or
                // databases under one bare table name. That's exactly the ambiguity
                // hasDuplicateColumnNames, findDrift's fallback comparison, and
                // enrichWithMySqlCharsetAndCollation's skip-and-warn logic all exist to handle.
                String normalizedTableName = DatabaseUtil.ColumnCheckInfo.fixupTableName(
                        rs.getString("TABLE_NAME"), null, needsUpperCase);
                // NULLABLE is documented by DatabaseMetaData.getColumns as one of columnNoNulls,
                // columnNullable, or columnNullableUnknown - compare against columnNullable
                // explicitly rather than treating the raw int as a boolean.
                boolean nullable = rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
                ColumnSignature column = ColumnSignature.withoutCharset(
                        rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        nullable);
                columnsByTable.computeIfAbsent(normalizedTableName, key -> new LinkedHashSet<>()).add(column);
            }
        }
        return enrichWithMySqlCharsetAndCollation(conn, schemaName, columnsByTable);
    }

    private static boolean isMySql(Connection conn) throws SQLException {
        return conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
    }

    /**
     * Enriches {@code columnsByTable} with each column's MySQL charset/collation. MySQL is the
     * only supported vendor with a meaningful per-column charset/collation concept - H2 and
     * Postgres have nothing equivalent - so this method is a genuine no-op for every other
     * vendor: the {@code isMySql} check below gates the entire body, not just the query's
     * execution, so no MySQL-specific SQL is ever built (let alone run) on a non-MySQL
     * connection. This vendor-specific enrichment has no dedicated unit test, since this
     * codebase's test infrastructure is H2-only and no real MySQL instance is available to
     * exercise it; that is intentional, consistent with how this codebase already documents
     * other vendor-specific behavior it cannot exercise in H2-only tests.
     *
     * <p>{@link #columnsBySchema} can now be invoked with a non-{@code null} schema name, via {@link
     * #findDrift(List, String, String)}'s overload. That does not help here, though: on MySQL,
     * {@code MigrationSupport.resolveSchemaName} always resolves to {@code null} regardless of which
     * {@code findDrift} overload a caller uses, because MySQL Connector/J reports {@code
     * supportsSchemasInTableDefinitions() == false} (MySQL treats databases as catalogs, not
     * schemas) - see {@code findDrift(List, String, String)}'s javadoc. So on MySQL specifically,
     * {@code columnsBySchema} is still effectively called unscoped, and with the common driver
     * default {@code nullCatalogMeansCurrent=false}, an unscoped {@code DatabaseMetaData.getColumns}
     * call can return columns for every database on the server merged under bare table names - so a
     * table's {@code Set<ColumnSignature>} can legitimately contain more than one entry with the
     * same {@code columnName()}, each really belonging to a different database. When that happens
     * for a given table, a {@code (TABLE_NAME, COLUMN_NAME)} row read back from {@code
     * information_schema.columns} (which is itself scoped to a single database via {@code
     * TABLE_SCHEMA = ?}) cannot be reliably matched to the one live signature it actually describes,
     * so enrichment is skipped for that table entirely rather than risk attributing one database's
     * charset/collation to a different database's column.
     */
    private static Map<String, Set<ColumnSignature>> enrichWithMySqlCharsetAndCollation(Connection conn,
            String schemaName, Map<String, Set<ColumnSignature>> columnsByTable) throws SQLException {
        if (!isMySql(conn)) {
            return columnsByTable;
        }
        DatabaseMetaData metaData = conn.getMetaData();
        boolean needsUpperCase = metaData.storesLowerCaseIdentifiers() || metaData.storesMixedCaseIdentifiers();
        // MySQL's information_schema.columns.TABLE_SCHEMA corresponds to what other RDBMSes call
        // the database name; when no schema was explicitly requested, fall back to the
        // connection's current database.
        String schema = schemaName == null || schemaName.isBlank() ? conn.getCatalog() : schemaName;

        Map<String, Set<ColumnSignature>> enriched = new HashMap<>();
        Set<String> ambiguousTables = new HashSet<>();
        for (Map.Entry<String, Set<ColumnSignature>> entry : columnsByTable.entrySet()) {
            enriched.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            if (hasDuplicateColumnNames(entry.getValue())) {
                ambiguousTables.add(entry.getKey());
            }
        }
        if (!ambiguousTables.isEmpty()) {
            logAmbiguousTablesWarning(ambiguousTables);
        }

        try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME "
                        + "FROM information_schema.columns WHERE TABLE_SCHEMA = ?")) {
            stmt.setString(1, schema);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String normalizedTableName = DatabaseUtil.ColumnCheckInfo.fixupTableName(
                            rs.getString("TABLE_NAME"), null, needsUpperCase);
                    if (ambiguousTables.contains(normalizedTableName)) {
                        continue;
                    }
                    Set<ColumnSignature> columns = enriched.get(normalizedTableName);
                    if (columns == null) {
                        continue;
                    }
                    String columnName = rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT);
                    String characterSet = rs.getString("CHARACTER_SET_NAME");
                    String collation = rs.getString("COLLATION_NAME");
                    String charsetAndCollation = (characterSet == null ? "" : characterSet) + "/"
                            + (collation == null ? "" : collation);

                    ColumnSignature match = null;
                    for (ColumnSignature candidate : columns) {
                        if (candidate.columnName().equals(columnName)) {
                            match = candidate;
                            break;
                        }
                    }
                    if (match != null) {
                        columns.remove(match);
                        columns.add(new ColumnSignature(match.columnName(), match.typeName(), match.columnSize(),
                                match.decimalDigits(), match.nullable(), charsetAndCollation));
                    }
                }
            }
        }
        return enriched;
    }

    /**
     * Logs a single aggregated warning for every table {@link #enrichWithMySqlCharsetAndCollation}
     * skipped in one invocation because of ambiguous, same-named columns, instead of one warning per
     * table. A MySQL server hosting several OFBiz databases can have nearly every table name collide
     * across databases, and this method is invoked repeatedly per component at every application
     * boot (via {@link #columnsBySchema}), so logging per-table would multiply into thousands of
     * near-duplicate lines; aggregating to one call per invocation keeps the same causal explanation
     * without drowning out the rest of the log. Package-private (rather than {@code private}) so it
     * can be unit tested directly via {@code Mockito.mockStatic(Debug.class)}, since the MySQL-gated
     * caller that relies on it has no dedicated test coverage (see {@link
     * #enrichWithMySqlCharsetAndCollation}'s Javadoc).
     */
    static void logAmbiguousTablesWarning(Set<String> ambiguousTables) {
        List<String> sortedTableNames = new ArrayList<>(new TreeSet<>(ambiguousTables));
        List<String> shownTableNames = sortedTableNames.size() > MAX_AMBIGUOUS_TABLES_LOGGED
                ? sortedTableNames.subList(0, MAX_AMBIGUOUS_TABLES_LOGGED)
                : sortedTableNames;
        StringBuilder tableNameList = new StringBuilder(String.join(", ", shownTableNames));
        int omittedCount = sortedTableNames.size() - shownTableNames.size();
        if (omittedCount > 0) {
            tableNameList.append(", +").append(omittedCount).append(" more");
        }
        Debug.logWarning(sortedTableNames.size() + " table(s) have more than one column reported under the same "
                + "name (likely columnsBySchema's unscoped getColumns call merging same-named tables from "
                + "multiple databases on this MySQL server) - skipping MySQL charset/collation enrichment for "
                + "these tables, since a (TABLE_NAME, COLUMN_NAME) row from information_schema.columns cannot be "
                + "reliably attributed to one of the ambiguous live column signatures for each; their signatures "
                + "are left without charset/collation data. Affected tables: " + tableNameList, MODULE);
    }

    /**
     * Returns whether {@code columns} contains more than one {@link ColumnSignature} sharing the
     * same {@code columnName()} - the signal that a table's column set is ambiguous (most likely
     * because it merges same-named columns from more than one database), so a {@code
     * (TABLE_NAME, COLUMN_NAME)} row read back separately cannot be matched to a single one of
     * them with confidence. Package-private (rather than {@code private}) so it can be unit tested
     * directly, since the MySQL-gated caller that relies on it has no dedicated test coverage (see
     * {@link #enrichWithMySqlCharsetAndCollation}'s Javadoc).
     */
    static boolean hasDuplicateColumnNames(Set<ColumnSignature> columns) {
        Set<String> seenColumnNames = new HashSet<>();
        for (ColumnSignature column : columns) {
            if (!seenColumnNames.add(column.columnName())) {
                return true;
            }
        }
        return false;
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
