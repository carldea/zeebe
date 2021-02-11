/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.0. You may not use this file
 * except in compliance with the Zeebe Community License 1.0.
 */
package io.zeebe.broker.it.util;

import static io.zeebe.test.util.TestUtil.waitUntil;
import static org.assertj.core.api.Assertions.assertThat;

import io.zeebe.broker.TestLoggers;
import io.zeebe.broker.it.clustering.ClusteringRule;
import io.zeebe.broker.test.EmbeddedBrokerRule;
import io.zeebe.client.ZeebeClient;
import io.zeebe.client.ZeebeClientBuilder;
import io.zeebe.client.api.response.DeploymentEvent;
import io.zeebe.client.api.response.PartitionInfo;
import io.zeebe.client.api.response.Topology;
import io.zeebe.model.bpmn.Bpmn;
import io.zeebe.model.bpmn.BpmnModelInstance;
import io.zeebe.model.bpmn.builder.ServiceTaskBuilder;
import io.zeebe.protocol.record.Record;
import io.zeebe.protocol.record.intent.DeploymentIntent;
import io.zeebe.protocol.record.intent.JobIntent;
import io.zeebe.test.util.record.RecordingExporter;
import io.zeebe.util.SocketUtil;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.rules.ExternalResource;
import org.slf4j.Logger;

public final class GrpcClientRule extends ExternalResource {

  private static final Logger LOG = TestLoggers.TEST_LOGGER;

  protected ZeebeClient client;
  private final Consumer<ZeebeClientBuilder> configurator;
  private long startTime;

  public GrpcClientRule(final EmbeddedBrokerRule brokerRule) {
    this(brokerRule, config -> {});
  }

  public GrpcClientRule(
      final EmbeddedBrokerRule brokerRule, final Consumer<ZeebeClientBuilder> configurator) {
    this(
        config -> {
          config
              .gatewayAddress(
                  io.zeebe.util.SocketUtil.toHostAndPortString(brokerRule.getGatewayAddress()))
              .usePlaintext();
          configurator.accept(config);
        });
  }

  public GrpcClientRule(final ClusteringRule clusteringRule) {
    this(
        config ->
            config
                .gatewayAddress(SocketUtil.toHostAndPortString(clusteringRule.getGatewayAddress()))
                .usePlaintext());
  }

  public GrpcClientRule(final Consumer<ZeebeClientBuilder> configurator) {
    this.configurator = configurator;
  }

  @Override
  public void before() {
    startTime = System.currentTimeMillis();
    final ZeebeClientBuilder builder =
        ZeebeClient.newClientBuilder().defaultRequestTimeout(Duration.ofSeconds(10));
    configurator.accept(builder);
    client = builder.build();
    LOG.info("\n====\nClient startup time: {}\n====\n", (System.currentTimeMillis() - startTime));
    startTime = System.currentTimeMillis();
  }

  @Override
  public void after() {
    LOG.info(
        "Client Rule assumption: Test execution time: " + (System.currentTimeMillis() - startTime));
    startTime = System.currentTimeMillis();
    client.close();
    LOG.info("Client closing time: " + (System.currentTimeMillis() - startTime));
    client = null;
  }

  public ZeebeClient getClient() {
    return client;
  }

  public void waitUntilDeploymentIsDone(final long key) {
    waitUntil(
        () ->
            RecordingExporter.deploymentRecords(DeploymentIntent.DISTRIBUTED)
                .withRecordKey(key)
                .exists());
  }

  public List<Integer> getPartitions() {
    final Topology topology = client.newTopologyRequest().send().join();

    return topology.getBrokers().stream()
        .flatMap(i -> i.getPartitions().stream())
        .filter(PartitionInfo::isLeader)
        .map(PartitionInfo::getPartitionId)
        .collect(Collectors.toList());
  }

  public long createSingleJob(final String type) {
    return createSingleJob(type, b -> {}, "{}");
  }

  public long createSingleJob(final String type, final Consumer<ServiceTaskBuilder> consumer) {
    return createSingleJob(type, consumer, "{}");
  }

  public long createSingleJob(
      final String type, final Consumer<ServiceTaskBuilder> consumer, final String variables) {
    return createJobs(type, consumer, variables, 1).get(0);
  }

  public List<Long> createJobs(final String type, final int amount) {
    return createJobs(type, b -> {}, "{}", amount);
  }

  public List<Long> createJobs(
      final String type,
      final Consumer<ServiceTaskBuilder> consumer,
      final String variables,
      final int amount) {

    final BpmnModelInstance modelInstance = createSingleJobModelInstance(type, consumer);
    final long processKey = deployProcess(modelInstance);

    final var processInstanceKeys =
        IntStream.range(0, amount)
            .boxed()
            .map(i -> createProcessInstance(processKey, variables))
            .collect(Collectors.toList());

    final List<Long> jobKeys =
        RecordingExporter.jobRecords(JobIntent.CREATED)
            .withType(type)
            .filter(r -> processInstanceKeys.contains(r.getValue().getProcessInstanceKey()))
            .limit(amount)
            .map(Record::getKey)
            .collect(Collectors.toList());

    assertThat(jobKeys).describedAs("Expected %d created jobs", amount).hasSize(amount);

    return jobKeys;
  }

  public BpmnModelInstance createSingleJobModelInstance(
      final String jobType, final Consumer<ServiceTaskBuilder> taskBuilderConsumer) {
    return Bpmn.createExecutableProcess("process")
        .startEvent("start")
        .serviceTask(
            "task",
            t -> {
              t.zeebeJobType(jobType);
              taskBuilderConsumer.accept(t);
            })
        .endEvent("end")
        .done();
  }

  public long deployProcess(final BpmnModelInstance modelInstance) {
    final DeploymentEvent deploymentEvent =
        getClient().newDeployCommand().addProcessModel(modelInstance, "process.bpmn").send().join();
    waitUntilDeploymentIsDone(deploymentEvent.getKey());
    return deploymentEvent.getProcesss().get(0).getProcessKey();
  }

  public long createProcessInstance(final long processKey, final String variables) {
    return getClient()
        .newCreateInstanceCommand()
        .processKey(processKey)
        .variables(variables)
        .send()
        .join()
        .getProcessInstanceKey();
  }

  public long createProcessInstance(final long processKey) {
    return getClient()
        .newCreateInstanceCommand()
        .processKey(processKey)
        .send()
        .join()
        .getProcessInstanceKey();
  }
}
