package io.perfana.scheduler;

import io.perfana.event.PerfanaEventConfig;
import io.perfana.event.wiremock.WiremockEventConfig;
import io.perfana.events.commandrunner.CommandRunnerEventConfig;
import io.perfana.eventscheduler.EventScheduler;
import io.perfana.eventscheduler.EventSchedulerBuilder;
import io.perfana.eventscheduler.api.EventLogger;
import io.perfana.eventscheduler.api.SchedulerExceptionHandler;
import io.perfana.eventscheduler.api.config.EventConfig;
import io.perfana.eventscheduler.api.config.EventSchedulerConfig;
import io.perfana.eventscheduler.api.config.TestConfig;
import io.perfana.eventscheduler.log.EventLoggerStdOut;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class CircuitBreakerTestScheduler {

    public static void main(String[] args) {

        // Enable debug: use INSTANCE_DEBUG
        EventLogger eventLogger = EventLoggerStdOut.INSTANCE;
        //EventLogger eventLogger = EventLoggerStdOut.INSTANCE_DEBUG;

        final int rampupTimeInSeconds = 10;
        final int constantLoadTimeInSeconds = 140; // Extended test duration for more detailed circuit breaker behavior
        final int totalRunTimeInSeconds = rampupTimeInSeconds + constantLoadTimeInSeconds;
        final int totalSleepSecondsPlusSlack = totalRunTimeInSeconds + 10;

        final String perfanaApiKey = System.getenv("PERFANA_API_KEY");
        final String perfanaUrl = getEnvOrDefault("PERFANA_URL", "http://localhost:4000");
        final String influxUrl = getEnvOrDefault("INFLUX_URL", "http://localhost:8086");
        final String influxDb = getEnvOrDefault("INFLUX_DB_K6", "k6");
        final String influxDbUsername = getEnvOrDefault("INFLUX_USER", "admin");
        final String influxDbPassword = getEnvOrDefault("INFLUX_PASSWORD", "admin");
        final boolean enableJfrAgent = getEnvOrDefault("ENABLE_JFR_AGENT", "false").equalsIgnoreCase("true");
        final boolean enableOtelAgent = getEnvOrDefault("ENABLE_OTEL_AGENT", "false").equalsIgnoreCase("true");

        final String testEnvironment = "silver";
        final String workload = "circuit-breaker-test";
        final String systemUnderTest = "tiny-bank";

        final List<String> tagsBuilder = new ArrayList<>();
        if (enableJfrAgent) tagsBuilder.add("jfr");
        if (enableOtelAgent) tagsBuilder.add("otel");
        tagsBuilder.add("k6");
        tagsBuilder.add("circuit-breaker");
        final List<String> tags = Collections.unmodifiableList(tagsBuilder);

        TestConfig testConfig = TestConfig.builder()
                .workload(workload)
                .tags(tags)
                .testEnvironment(testEnvironment)
                .systemUnderTest(systemUnderTest)
                .buildResultsUrl("http://perfana.io")
                .version("1.0.0")
                .rampupTimeInSeconds(rampupTimeInSeconds)
                .constantLoadTimeInSeconds(constantLoadTimeInSeconds)
                .annotations("Circuit breaker test with gradual delay progression to test recovery patterns.")
                .build();

        List<EventConfig> eventConfigs = new ArrayList<>();

        {
            if (perfanaApiKey == null) {
                System.err.println("PERFANA_API_KEY environment variable not set, skipping Perfana event.");
            }
            else {
                PerfanaEventConfig perfanaEventConfig = new PerfanaEventConfig();
                perfanaEventConfig.setName("perfana-event");
                perfanaEventConfig.setApiKey(perfanaApiKey);
                perfanaEventConfig.setPerfanaUrl(perfanaUrl);
                eventConfigs.add(perfanaEventConfig);
            }
        }

        {
            List<String> arguments = new ArrayList<>();
            if (enableJfrAgent) arguments.add("--jfr-agent");
            if (enableOtelAgent) arguments.add("--otel-agent");

            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("command-runner-wait-for-start");
            // wait for script to finish before starting the test
            commandConfig.setReadyForStartParticipant(true);
            commandConfig.setOnBeforeTest("./start-test-components.sh " + String.join(" ", arguments));
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("command-runner-k6-to-influx");
            commandConfig.setOnStartTest("./x2i . -i k6 -u " + influxDbUsername + " -p " + influxDbPassword + " -a " + influxUrl + " -b " + influxDb + " -t " + testEnvironment + " -y " + systemUnderTest + " -s " + totalSleepSecondsPlusSlack);
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("command-runner-k6");
            commandConfig.setOnStartTest("export TEST_RUN_ID=__testRunId__; export DURATION=" + totalRunTimeInSeconds + "s; k6 run --quiet --out csv=test_results.csv loadtest/k6_load_test.js");
            eventConfigs.add(commandConfig);
        }

        {
            WiremockEventConfig wiremockAccount = new WiremockEventConfig();
            wiremockAccount.setWiremockFilesDir("loadtest/mappers-account");
            wiremockAccount.setName("wiremock-account");
            wiremockAccount.setWiremockUrl("http://localhost:30123");
            eventConfigs.add(wiremockAccount);
        }

        {
            WiremockEventConfig wiremockBalance = new WiremockEventConfig();
            wiremockBalance.setWiremockFilesDir("loadtest/mappers-balance");
            wiremockBalance.setName("wiremock-balance");
            wiremockBalance.setWiremockUrl("http://localhost:30124");
            eventConfigs.add(wiremockBalance);
        }

//        // New gradual delay script that increases latency in smaller increments around the circuit breaker thresholds
//        // for both balance and account services
//        String circuitBreakerTestScript =
//                """
//PT10S|run-command(starting-balance-200ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=200
//PT10S|run-command(starting-account-200ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=200
//PT10S|run-command(annotate-phase1)|name=alerts;text=Phase 1: Starting with 200ms delay
//
//PT30S|run-command(approach-balance-500ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=500
//PT30S|run-command(approach-account-500ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=500
//PT30S|run-command(annotate-phase2)|name=alerts;text=Phase 2: Approaching slow call threshold at 500ms
//
//PT50S|run-command(threshold-balance-800ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=800
//PT50S|run-command(threshold-account-400ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=400
//PT50S|run-command(annotate-phase3)|name=alerts;text=Phase 3: At slow call threshold for balance service
//
//PT70S|run-command(above-balance-850ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=850
//PT70S|run-command(above-account-400ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=400
//PT70S|run-command(annotate-phase4)|name=alerts;text=Phase 4: Just above slow call threshold for balance service
//
//PT90S|run-command(exceed-balance-1000ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1000
//PT90S|run-command(exceed-account-450ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=450
//PT90S|run-command(annotate-phase5)|name=alerts;text=Phase 5: Well above slow call threshold (1000ms)
//
//PT110S|run-command(extreme-balance-1200ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1200
//PT110S|run-command(extreme-account-500ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=500
//PT110S|run-command(annotate-phase6)|name=alerts;text=Phase 6: Extreme slowness to trigger circuit breaker
//
//PT130S|run-command(maintain-balance-1200ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1200
//PT130S|run-command(maintain-account-500ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=500
//PT130S|run-command(annotate-phase7)|name=alerts;text=Phase 7: Maintaining extreme slowness to keep circuit open
//
//PT150S|run-command(recovery-balance-700ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=700
//PT150S|run-command(recovery-account-300ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=300
//PT150S|run-command(annotate-phase8)|name=alerts;text=Phase 8: Beginning recovery (700ms)
//
//PT170S|run-command(further-balance-400ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=400
//PT170S|run-command(further-account-200ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=200
//PT170S|run-command(annotate-phase9)|name=alerts;text=Phase 9: Further recovery (400ms)
//
//PT190S|run-command(normal-balance-100ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=100
//PT190S|run-command(normal-account-100ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=100
//PT190S|run-command(annotate-phase10)|name=alerts;text=Phase 10: Back to normal (100ms)
//
//PT210S|run-command(spike-balance-1100ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1100
//PT210S|run-command(spike-account-500ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=500
//PT210S|run-command(annotate-phase11)|name=alerts;text=Phase 11: Sudden spike (1100ms)
//
//PT220S|run-command(quick-balance-100ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=100
//PT220S|run-command(quick-account-100ms)|name=toxiproxy;proxy_name=account-service;toxic_name=asLatency;latency_ms=100
//PT220S|run-command(annotate-phase12)|name=alerts;text=Phase 12: Quick recovery after spike
//                        """;

        // New failure rate script
        String circuitBreakerTestScript =
                """
PT10S|run-command(wiremock-failure-10)|name=wiremock-failures;failure_rate=10
PT10S|run-command(annotate-phase1)|name=alerts;text=Phase 1: Starting with failure rate 10 procent

PT30S|run-command(wiremock-failure-20)|name=wiremock-failures;failure_rate=20
PT30S|run-command(annotate-phase2)|name=alerts;text=Phase 2: Failure rate 20 procent

PT50S|run-command(wiremock-failure-30)|name=wiremock-failures;failure_rate=30
PT50S|run-command(annotate-phase3)|name=alerts;text=Phase 3: Failure rate 30 procent

PT70S|run-command(wiremock-failure-1)|name=wiremock-failures;failure_rate=1
PT70S|run-command(annotate-phase4)|name=alerts;text=Phase 4: Failure rate 1 procent

PT90S|run-command(wiremock-failure-50)|name=wiremock-failures;failure_rate=50
PT90S|run-command(annotate-phase5)|name=alerts;text=Phase 5: Failure rate 50 procent

PT110S|run-command(wiremock-failure-50)|name=wiremock-failures;failure_rate=100
PT110S|run-command(annotate-phase6)|name=alerts;text=Phase 6: Failure rate 100 procent

PT130S|run-command(wiremock-failure-50)|name=wiremock-failures;failure_rate=10
PT130S|run-command(annotate-phase7)|name=alerts;text=Phase 7: Failure rate 10 procent
                        """;

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("toxiproxy");
            // Setup both balance and account service latency toxics
            commandConfig.setOnStartTest("docker exec toxiproxy /go/bin/toxiproxy-cli toxic add -n bsLatency -t latency -a latency=0 balance-service && " +
                                        "docker exec toxiproxy /go/bin/toxiproxy-cli toxic add -n asLatency -t latency -a latency=0 account-service");
            commandConfig.setOnScheduledEvent("docker exec toxiproxy /go/bin/toxiproxy-cli toxic update -n __toxic_name__ -a latency=__latency_ms__ __proxy_name__");
            commandConfig.setOnAfterTest("docker exec toxiproxy /go/bin/toxiproxy-cli toxic remove -n bsLatency balance-service && " +
                                         "docker exec toxiproxy /go/bin/toxiproxy-cli toxic remove -n asLatency account-service");
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("wiremock-failures");
            commandConfig.setOnScheduledEvent("curl -Ss -X POST http://localhost:30123/admin/set-failure-rate -H \"Content-Type: application/json\" -d '{\"rate\": __failure_rate__}'" +
                    " && curl -Ss -X POST http://localhost:30124/admin/set-failure-rate -H \"Content-Type: application/json\" -d '{\"rate\": __failure_rate__}'");
            commandConfig.setOnAfterTest("curl -Ss -X POST http://localhost:30123/admin/set-failure-rate -H \"Content-Type: application/json\" -d '{\"rate\": 0}'" +
                    " && curl -Ss -X POST http://localhost:30124/admin/set-failure-rate -H \"Content-Type: application/json\" -d '{\"rate\": 0}'");
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("alerts");
            commandConfig.setOnStartTest("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\"],\"text\":\"Circuit breaker test started\"}' http://admin:admin@localhost:3000/api/annotations");
            commandConfig.setOnScheduledEvent("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\"],\"text\":\"__text__\"}' http://admin:admin@localhost:3000/api/annotations");
            commandConfig.setOnAfterTest("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\"],\"text\":\"Circuit breaker test ended\"}' http://admin:admin@localhost:3000/api/annotations");
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("command-runner-stop-processes");
            commandConfig.setOnAfterTest("./stop-test.sh");
            commandConfig.setOnAbort("./stop-test.sh");
            eventConfigs.add(commandConfig);
        }

        EventSchedulerConfig eventSchedulerConfig = EventSchedulerConfig.builder()
                .testConfig(testConfig)
                .eventConfigs(eventConfigs)
                .scheduleScript(circuitBreakerTestScript)
                .build();

        EventScheduler scheduler = EventSchedulerBuilder.of(eventSchedulerConfig, eventLogger);
        CountDownLatch abortLatch = new CountDownLatch(1);

        addKillSwitch(scheduler);

        // the shutdown hook will count down abortLatch when finished aborting
        registerShutdownHook(scheduler, abortLatch);

        scheduler.startSession();

        try {
            waitForTestToFinish(totalSleepSecondsPlusSlack);
        } finally {
            scheduler.stopSession();
        }

        try {
            println("Waiting for abort to finish");
            abortLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            println("Interrupted while waiting for abort to finish");
        }
    }

    private static String getEnvOrDefault(String envVar, String defaultValue) {
        return System.getenv(envVar) != null ? System.getenv(envVar) : defaultValue;
    }

    private static void registerShutdownHook(EventScheduler scheduler, CountDownLatch abortLatch) {
        Runtime.getRuntime().addShutdownHook(
                new Thread("app-shutdown-hook") {
                    @Override
                    public void run() {
                        println("SHUTDOWN detected: waiting for abort to finish");
                        if (scheduler.isSessionStopped()) {
                            println("SHUTDOWN detected: abort already done");
                            abortLatch.countDown();
                        }
                        else {
                            scheduler.abortSession();
                            println("SHUTDOWN detected: abort session done");
                            abortLatch.countDown();
                        }
                    }
                });
    }

    private static void addKillSwitch(EventScheduler scheduler) {
        scheduler.addKillSwitch(new SchedulerExceptionHandler() {
            @Override
            public void kill(String message) {
                println("Kill switch requested: " + message);
                scheduler.abortSession();
                System.exit(3);
            }

            @Override
            public void abort(String message) {
                println("Abort requested: " + message);
                scheduler.abortSession();
                sleep(3);
                System.exit(4);
            }

            @Override
            public void stop(String message) {
                println("Stop requested: " + message);
                scheduler.stopSession();
                System.exit(5);
            }
        });
    }

    private static void println(String text) {
        System.out.println(text);
    }

    private static void waitForTestToFinish(int totalSleepSeconds) {
        sleep(totalSleepSeconds);
    }

    private static void sleep(int totalSleepSeconds) {
        try {
            Thread.sleep(Duration.ofSeconds(totalSleepSeconds).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            println("Interrupted while waiting for test to finish");
        }
    }
}
