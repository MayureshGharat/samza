/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.runtime;

import java.time.Duration;
import java.util.UUID;
import org.apache.samza.SamzaException;
import org.apache.samza.application.StreamApplication;
import org.apache.samza.config.ApplicationConfig;
import org.apache.samza.config.Config;
import org.apache.samza.config.JobConfig;
import org.apache.samza.coordinator.stream.CoordinatorStreamSystemConsumer;
import org.apache.samza.execution.ExecutionPlan;
import org.apache.samza.execution.StreamManager;
import org.apache.samza.job.ApplicationStatus;
import org.apache.samza.job.JobRunner;
import org.apache.samza.metrics.MetricsRegistryMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.samza.job.ApplicationStatus.*;


/**
 * This class implements the {@link ApplicationRunner} that runs the applications in a remote cluster
 */
public class RemoteApplicationRunner extends AbstractApplicationRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RemoteApplicationRunner.class);
  private static final long DEFAULT_SLEEP_DURATION_MS = 2000;

  public RemoteApplicationRunner(Config config) {
    super(config);
  }

  @Override
  public void runTask() {
    throw new UnsupportedOperationException("Running StreamTask is not implemented for RemoteReplicationRunner");
  }

  /**
   * Run the {@link StreamApplication} on the remote cluster
   * @param app a StreamApplication
   */
  @Override
  public void run(StreamApplication app) {
    try {
      // TODO: run.id needs to be set for standalone: SAMZA-1531
      // run.id is based on current system time with the most significant bits in UUID (8 digits) to avoid collision
      String runId = String.valueOf(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString().substring(0, 8);
      LOG.info("The run id for this run is {}", runId);

      // 1. initialize and plan
      ExecutionPlan plan = getExecutionPlan(app, runId);
      writePlanJsonFile(plan.getPlanAsJson());

      plan.getJobConfigs().forEach(jobConfig -> {
          StreamManager streamManager = null;
          try {
            // 2. create the necessary streams
            streamManager = buildAndStartStreamManager(jobConfig);
            if (plan.getApplicationConfig().getAppMode() == ApplicationConfig.ApplicationMode.BATCH) {
              streamManager.clearStreamsFromPreviousRun(getConfigFromPrevRun());
            }
            streamManager.createStreams(plan.getIntermediateStreams());

            // 3. submit jobs for remote execution
            LOG.info("Starting job {} with config {}", jobConfig.getName(), jobConfig);
            JobRunner runner = new JobRunner(jobConfig);
            runner.run(true);
          } finally {
            if (streamManager != null) {
              streamManager.stop();
            }
          }
        });
    } catch (Throwable t) {
      throw new SamzaException("Failed to run application", t);
    }
  }

  @Override
  public void kill(StreamApplication app) {

    // since currently we only support single actual remote job, we can get its status without
    // building the execution plan.
    try {
      JobConfig jc = new JobConfig(config);
      LOG.info("Killing job {}", jc.getName());
      JobRunner runner = new JobRunner(jc);
      runner.kill();
    } catch (Throwable t) {
      throw new SamzaException("Failed to kill application", t);
    }
  }

  @Override
  public ApplicationStatus status(StreamApplication app) {

    // since currently we only support single actual remote job, we can get its status without
    // building the execution plan
    try {
      JobConfig jc = new JobConfig(config);
      return getApplicationStatus(jc);
    } catch (Throwable t) {
      throw new SamzaException("Failed to get status for application", t);
    }
  }

  /* package private */ ApplicationStatus getApplicationStatus(JobConfig jobConfig) {
    JobRunner runner = new JobRunner(jobConfig);
    ApplicationStatus status = runner.status();
    LOG.debug("Status is {} for job {}", new Object[]{status, jobConfig.getName()});
    return status;
  }

  /**
   * Waits until the application finishes.
   */
  public void waitForFinish() {
    waitForFinish(Duration.ofMillis(0));
  }

  /**
   * Waits for {@code timeout} duration for the application to finish.
   * If timeout &lt; 1, blocks the caller indefinitely.
   *
   * @param timeout time to wait for the application to finish
   * @return true - application finished before timeout
   *         false - otherwise
   */
  public boolean waitForFinish(Duration timeout) {
    JobConfig jobConfig = new JobConfig(config);
    boolean finished = true;
    long timeoutInMs = timeout.toMillis();
    long startTimeInMs = System.currentTimeMillis();
    long timeElapsed = 0L;

    long sleepDurationInMs = timeoutInMs < 1 ?
        DEFAULT_SLEEP_DURATION_MS : Math.min(timeoutInMs, DEFAULT_SLEEP_DURATION_MS);
    ApplicationStatus status;

    try {
      while (timeoutInMs < 1 || timeElapsed <= timeoutInMs) {
        status = getApplicationStatus(jobConfig);
        if (status == SuccessfulFinish || status == UnsuccessfulFinish) {
          LOG.info("Application finished with status {}", status);
          break;
        }

        Thread.sleep(sleepDurationInMs);
        timeElapsed = System.currentTimeMillis() - startTimeInMs;
      }

      if (timeElapsed > timeoutInMs) {
        LOG.warn("Timed out waiting for application to finish.");
        finished = false;
      }
    } catch (Exception e) {
      LOG.error("Error waiting for application to finish", e);
      throw new SamzaException(e);
    }

    return finished;
  }

  private Config getConfigFromPrevRun() {
    CoordinatorStreamSystemConsumer consumer = new CoordinatorStreamSystemConsumer(config, new MetricsRegistryMap());
    consumer.register();
    consumer.start();
    consumer.bootstrap();
    consumer.stop();

    Config cfg = consumer.getConfig();
    LOG.info("Previous config is: " + cfg.toString());
    return cfg;
  }
}
