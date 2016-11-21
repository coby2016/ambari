/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.CommandExecutionType;
import org.apache.ambari.server.controller.AmbariManagementController;
import org.apache.ambari.server.orm.DBAccessor;
import org.apache.ambari.server.orm.DBAccessor.DBColumnInfo;
import org.apache.ambari.server.orm.dao.DaoUtils;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * Upgrade catalog for version 2.5.0.
 */
public class UpgradeCatalog250 extends AbstractUpgradeCatalog {

  protected static final String HOST_VERSION_TABLE = "host_version";
  protected static final String GROUPS_TABLE = "groups";
  protected static final String GROUP_TYPE_COL = "group_type";
  private static final String AMS_ENV = "ams-env";
  private static final String KAFKA_BROKER = "kafka-broker";
  private static final String KAFKA_TIMELINE_METRICS_HOST = "kafka.timeline.metrics.host";

  public static final String COMPONENT_TABLE = "servicecomponentdesiredstate";
  public static final String COMPONENT_VERSION_TABLE = "servicecomponent_version";
  public static final String COMPONENT_VERSION_PK = "PK_sc_version";
  public static final String COMPONENT_VERSION_FK_COMPONENT = "FK_scv_component_id";
  public static final String COMPONENT_VERSION_FK_REPO_VERSION = "FK_scv_repo_version_id";

  protected static final String SERVICE_DESIRED_STATE_TABLE = "servicedesiredstate";
  protected static final String CREDENTIAL_STORE_SUPPORTED_COL = "credential_store_supported";
  protected static final String CREDENTIAL_STORE_ENABLED_COL = "credential_store_enabled";

  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog250.class);

  @Inject
  DaoUtils daoUtils;

  // ----- Constructors ------------------------------------------------------

  /**
   * Don't forget to register new UpgradeCatalogs in {@link org.apache.ambari.server.upgrade.SchemaUpgradeHelper.UpgradeHelperModule#configure()}
   *
   * @param injector Guice injector to track dependencies and uses bindings to inject them.
   */
  @Inject
  public UpgradeCatalog250(Injector injector) {
    super(injector);

    daoUtils = injector.getInstance(DaoUtils.class);
  }

  // ----- UpgradeCatalog ----------------------------------------------------

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTargetVersion() {
    return "2.5.0";
  }

  // ----- AbstractUpgradeCatalog --------------------------------------------

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSourceVersion() {
    return "2.4.2";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDDLUpdates() throws AmbariException, SQLException {
    updateHostVersionTable();
    createComponentVersionTable();
    updateGroupsTable();
    dbAccessor.addColumn("stage",
      new DBAccessor.DBColumnInfo("command_execution_type", String.class, 32, CommandExecutionType.STAGE.toString(),
        false));
    updateServiceDesiredStateTable();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executePreDMLUpdates() throws AmbariException, SQLException {

  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
    addNewConfigurationsFromXml();
    updateAMSConfigs();
    updateKafkaConfigs();
  }

  protected void updateHostVersionTable() throws SQLException {
    LOG.info("Updating the {} table", HOST_VERSION_TABLE);

    // Add the unique constraint to the host_version table
    dbAccessor.addUniqueConstraint(HOST_VERSION_TABLE, "UQ_host_repo", "repo_version_id", "host_id");
  }

  protected void updateGroupsTable() throws SQLException {
    LOG.info("Updating the {} table", GROUPS_TABLE);

    dbAccessor.addColumn(GROUPS_TABLE, new DBColumnInfo(GROUP_TYPE_COL, String.class, null, "LOCAL", false));
    dbAccessor.executeQuery("UPDATE groups SET group_type='LDAP' WHERE ldap_group=1");
    dbAccessor.addUniqueConstraint(GROUPS_TABLE, "UNQ_groups_0", "group_name", "group_type");
  }

  protected void updateAMSConfigs() throws AmbariException {
    AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    Clusters clusters = ambariManagementController.getClusters();

    if (clusters != null) {
      Map<String, Cluster> clusterMap = clusters.getClusters();

      if (clusterMap != null && !clusterMap.isEmpty()) {
        for (final Cluster cluster : clusterMap.values()) {

          Config amsEnv = cluster.getDesiredConfigByType(AMS_ENV);
          if (amsEnv != null) {
            Map<String, String> amsEnvProperties = amsEnv.getProperties();
            String content = amsEnvProperties.get("content");
            Map<String, String> newProperties = new HashMap<>();
            newProperties.put("content", updateAmsEnvContent(content));
            updateConfigurationPropertiesForCluster(cluster, AMS_ENV, newProperties, true, true);
          }

        }
      }
    }
  }


  protected String updateAmsEnvContent(String content) {
    if (content == null) {
      return null;
    }

    List<String> toReplaceList = new ArrayList<>();
    toReplaceList.add("\n# HBase normalizer enabled\n");
    toReplaceList.add("\n# HBase compaction policy enabled\n");
    toReplaceList.add("export AMS_HBASE_NORMALIZER_ENABLED={{ams_hbase_normalizer_enabled}}\n");
    toReplaceList.add("export AMS_HBASE_FIFO_COMPACTION_ENABLED={{ams_hbase_fifo_compaction_enabled}}\n");

    //Because of AMBARI-15331 : AMS HBase FIFO compaction policy and Normalizer settings are not handled correctly
    toReplaceList.add("export HBASE_NORMALIZATION_ENABLED={{ams_hbase_normalizer_enabled}}\n");
    toReplaceList.add("export HBASE_FIFO_COMPACTION_POLICY_ENABLED={{ams_hbase_fifo_compaction_policy_enabled}}\n");


    for (String toReplace : toReplaceList) {
      if (content.contains(toReplace)) {
        content = content.replace(toReplace, StringUtils.EMPTY);
      }
    }

    return content;
  }

  /**
   * Creates the servicecomponent_version table
   *
   * @throws SQLException
   */
  private void createComponentVersionTable() throws SQLException {

    List<DBColumnInfo> columns = new ArrayList<>();

    // Add extension link table
    LOG.info("Creating {} table", COMPONENT_VERSION_TABLE);

    columns.add(new DBColumnInfo("id", Long.class, null, null, false));
    columns.add(new DBColumnInfo("component_id", Long.class, null, null, false));
    columns.add(new DBColumnInfo("repo_version_id", Long.class, null, null, false));
    columns.add(new DBColumnInfo("state", String.class, 32, null, false));
    columns.add(new DBColumnInfo("user_name", String.class, 255, null, false));
    dbAccessor.createTable(COMPONENT_VERSION_TABLE, columns, (String[]) null);

    dbAccessor.addPKConstraint(COMPONENT_VERSION_TABLE, COMPONENT_VERSION_PK, "id");

    dbAccessor.addFKConstraint(COMPONENT_VERSION_TABLE, COMPONENT_VERSION_FK_COMPONENT, "component_id",
      COMPONENT_TABLE, "id", false);

    dbAccessor.addFKConstraint(COMPONENT_VERSION_TABLE, COMPONENT_VERSION_FK_REPO_VERSION, "repo_version_id",
      "repo_version", "repo_version_id", false);

    addSequence("servicecomponent_version_id_seq", 0L, false);
  }

  protected void updateKafkaConfigs() throws AmbariException {
    AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    Clusters clusters = ambariManagementController.getClusters();

    if (clusters != null) {
      Map<String, Cluster> clusterMap = clusters.getClusters();

      if (clusterMap != null && !clusterMap.isEmpty()) {
        for (final Cluster cluster : clusterMap.values()) {

          Config kafkaBrokerConfig = cluster.getDesiredConfigByType(KAFKA_BROKER);
          if (kafkaBrokerConfig != null) {
            Map<String, String> kafkaBrokerProperties = kafkaBrokerConfig.getProperties();

            if (kafkaBrokerProperties != null && kafkaBrokerProperties.containsKey(KAFKA_TIMELINE_METRICS_HOST)) {
              LOG.info("Removing kafka.timeline.metrics.host from kafka-broker");
              removeConfigurationPropertiesFromCluster(cluster, KAFKA_BROKER, Collections.singleton("kafka.timeline.metrics.host"));
            }
          }
        }
      }
    }
  }

  /**
   * Alter servicedesiredstate table.
   * @throws SQLException
   */
  private void updateServiceDesiredStateTable() throws SQLException {
    // ALTER TABLE servicedesiredstate ADD COLUMN
    // credential_store_supported SMALLINT DEFAULT 0 NOT NULL
    // credential_store_enabled SMALLINT DEFAULT 0 NOT NULL
    dbAccessor.addColumn(SERVICE_DESIRED_STATE_TABLE,
      new DBColumnInfo(CREDENTIAL_STORE_SUPPORTED_COL, Short.class, null, 0, false));

    dbAccessor.addColumn(SERVICE_DESIRED_STATE_TABLE,
      new DBColumnInfo(CREDENTIAL_STORE_ENABLED_COL, Short.class, null, 0, false));
  }
}
