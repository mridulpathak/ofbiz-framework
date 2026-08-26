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
package org.apache.ofbiz.entity.config.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class DatasourceTests {

    @Test
    void schemaManagementStrategyDefaultsToAutoDdlWhenAbsent() throws Exception {
        Element element = parseDatasourceElement(
                "<datasource name=\"test1\" helper-class=\"org.apache.ofbiz.entity.datasource.GenericHelperDAO\" "
                        + "field-type-name=\"h2\"/>");

        Datasource datasource = new Datasource(element);

        assertEquals("auto-ddl", datasource.getSchemaManagementStrategy());
    }

    @Test
    void schemaManagementStrategyReadsExplicitFlywayValue() throws Exception {
        Element element = parseDatasourceElement(
                "<datasource name=\"test2\" helper-class=\"org.apache.ofbiz.entity.datasource.GenericHelperDAO\" "
                        + "field-type-name=\"mysql\" schema-management-strategy=\"flyway\"/>");

        Datasource datasource = new Datasource(element);

        assertEquals("flyway", datasource.getSchemaManagementStrategy());
    }

    private Element parseDatasourceElement(String xml) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return document.getDocumentElement();
    }
}
