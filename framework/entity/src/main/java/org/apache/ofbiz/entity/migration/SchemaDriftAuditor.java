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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
        for (String tableName : tableNames) {
            try {
                Set<String> liveColumns = columnNames(liveSchema, tableName);
                Set<String> referenceColumns = columnNames(referenceSchema, tableName);

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

    private Set<String> columnNames(Connection conn, String tableName) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        DatabaseMetaData metaData = conn.getMetaData();
        try (ResultSet rs = metaData.getColumns(null, null, tableName, null)) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toUpperCase());
            }
        }
        return columns;
    }
}
