/**
 * Copyright 2014 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.kafka.schemaregistry;

import org.I0Itec.zkclient.ZkClient;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Properties;
import java.util.Queue;
import java.util.Vector;

import io.confluent.kafka.schemaregistry.avro.AvroCompatibilityType;
import io.confluent.kafka.schemaregistry.rest.SchemaRegistryConfig;
import kafka.server.KafkaConfig;
import kafka.server.KafkaServer;
import kafka.utils.SystemTime$;
import kafka.utils.TestUtils;
import kafka.utils.Utils;
import kafka.utils.ZKStringSerializer$;
import kafka.zk.EmbeddedZookeeper;
import scala.collection.JavaConversions;

/**
 * Test harness to run against a real, local Kafka cluster and REST proxy. This is essentially
 * Kafka's ZookeeperTestHarness and KafkaServerTestHarness traits combined and ported to Java with
 * the addition of the REST proxy. Defaults to a 1-ZK, 3-broker, 1 REST proxy cluster.
 */
public abstract class ClusterTestHarness {

  public static final int DEFAULT_NUM_BROKERS = 1;
  public static final String KAFKASTORE_TOPIC = SchemaRegistryConfig.DEFAULT_KAFKASTORE_TOPIC;

  // Shared config
  protected Queue<Integer> ports;

  // ZK Config
  protected int zkPort;
  protected String zkConnect;
  protected EmbeddedZookeeper zookeeper;
  protected ZkClient zkClient;
  protected int zkConnectionTimeout = 6000;
  protected int zkSessionTimeout = 6000;

  // Kafka Config
  protected List<KafkaConfig> configs = null;
  protected List<KafkaServer> servers = null;
  protected String brokerList = null;

  protected String bootstrapServers = null;

  protected RestApp restApp = null;

  public ClusterTestHarness() {
    this(DEFAULT_NUM_BROKERS);
  }

  public ClusterTestHarness(int numBrokers) {
    this(numBrokers, false);
  }

  public ClusterTestHarness(int numBrokers, boolean setupRestApp) {
    this(numBrokers, setupRestApp, AvroCompatibilityType.NONE.name);
  }

  public ClusterTestHarness(int numBrokers, boolean setupRestApp, String compatibilityType) {
    // 1 port for ZK, 1 port per broker, and 1 port for the rest App if needed
    int numPorts = 1 + numBrokers + (setupRestApp ? 1 : 0);

    ports = new ArrayDeque<Integer>();
    for (Object portObj : JavaConversions.asJavaList(TestUtils.choosePorts(numPorts))) {
      ports.add((Integer) portObj);
    }
    zkPort = ports.remove();
    zkConnect = String.format("localhost:%d", zkPort);

    configs = new Vector<KafkaConfig>();
    bootstrapServers = "";
    for (int i = 0; i < numBrokers; i++) {
      int port = ports.remove();
      Properties props = TestUtils.createBrokerConfig(i, port, false);
      props.setProperty("auto.create.topics.enable", "true");
      props.setProperty("num.partitions", "1");
      // We *must* override this to use the port we allocated (Kafka currently allocates one port
      // that it always uses for ZK
      props.setProperty("zookeeper.connect", this.zkConnect);
      configs.add(new KafkaConfig(props));

      if (bootstrapServers.length() > 0) {
        bootstrapServers += ",";
      }
      bootstrapServers = bootstrapServers + "localhost:" + ((Integer) port).toString();
    }

    if (setupRestApp) {
      int restPort = ports.remove();
      restApp = new RestApp(restPort, zkConnect, KAFKASTORE_TOPIC, compatibilityType);
    }
  }

  @Before
  public void setUp() throws Exception {
    zookeeper = new EmbeddedZookeeper(zkConnect);
    zkClient =
        new ZkClient(zookeeper.connectString(), zkSessionTimeout, zkConnectionTimeout,
                     ZKStringSerializer$.MODULE$);

    if (configs == null || configs.size() <= 0) {
      throw new RuntimeException("Must supply at least one server config.");
    }
    brokerList =
        TestUtils.getBrokerListStrFromConfigs(JavaConversions.asScalaIterable(configs).toSeq());
    servers = new Vector<KafkaServer>(configs.size());
    for (KafkaConfig config : configs) {
      KafkaServer server = TestUtils.createServer(config, SystemTime$.MODULE$);
      servers.add(server);
    }

    if (restApp != null) {
      restApp.start();
    }
  }

  @After
  public void tearDown() throws Exception {
    if (restApp != null) {
      restApp.stop();
    }

    for (KafkaServer server : servers) {
      server.shutdown();
    }
    for (KafkaServer server : servers) {
      for (String logDir : JavaConversions.asJavaCollection(server.config().logDirs())) {
        Utils.rm(logDir);
      }
    }

    zkClient.close();
    zookeeper.shutdown();
  }
}
