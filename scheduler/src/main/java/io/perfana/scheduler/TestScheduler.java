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

public class TestScheduler {

    public static void main(String[] args) {

        // Enable debug: use INSTANCE_DEBUG
        //EventLogger eventLogger = EventLoggerStdOut.INSTANCE;
        EventLogger eventLogger = EventLoggerStdOut.INSTANCE_DEBUG;

        final int rampupTimeInSeconds = 10;
        final int constantLoadTimeInSeconds = 120;
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
        final boolean isSlowDatabaseActive = getEnvOrDefault("IS_SLOW_DB_TEST", "false").equalsIgnoreCase("true");

        final String testEnvironment = "silver";
        final String workload = "load-resilience";
        final String systemUnderTest = "tiny-bank";

        final List<String> tagsBuilder = new ArrayList<>();
        if (enableJfrAgent) tagsBuilder.add("jfr");
        if (enableOtelAgent) tagsBuilder.add("otel");
        tagsBuilder.add("k6");
        tagsBuilder.add("spring-boot-kubernetes");
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
                .annotations("First resilience test with balance service delays up to 2 seconds.")
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

//        String scheduleScriptSlowRemoteServices =
//                """
//                    PT2S|wiremock-change-mappings(no-0ms)|delay_account=0;delay_balance=0
//                    PT30S|wiremock-change-mappings(short-100ms)|delay_account=100;delay_balance=100
//                    PT60S|wiremock-change-mappings(slow-500ms)|delay_account=500;delay_balance=500
//                    PT120S|wiremock-change-mappings(balance-really-slow-1s)|delay_account=500;delay_balance=1000
//                    PT180S|wiremock-change-mappings(balance-really-slow-2s)|delay_account=500;delay_balance=2000
//                    PT220S|wiremock-change-mappings(short-delay-100ms)|delay_account=100;delay_balance=100
//                """;

        String scheduleScriptSlowDatabase =
                """
                    PT30S|run-command(short-db-200ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=200
                    PT90S|run-command(slow-db-500ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=500
                    PT180S|run-command(slow-db-800ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=800
                    PT220S|run-command(slow-db-1400ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=1400
                    PT240S|run-command(slow-db-2000ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=2000
                    PT280S|run-command(fast-db-10ms)|name=toxiproxy;proxy_name=test-postgres;toxic_name=pgLatency;latency_ms=10
                """;

        // TODO duplicated so also alerts is receiving events: missing - multiple listeners for events? now based on name
        String scheduleScriptSlowBalance =
                """
                    PT20S|run-command(short-balance-200ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=200
                    PT20S|run-command(short-balance-200ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=200
                    PT40S|run-command(slow-balance-800ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=800
                    PT40S|run-command(slow-balance-800ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=800
                    PT60S|run-command(slow-balance-1000ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1000
                    PT60S|run-command(slow-balance-1000ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1000
                    PT80S|run-command(slow-balance-1200ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1200
                    PT80S|run-command(slow-balance-1200ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=1200
                    PT100S|run-command(slow-balance-2000ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=2000
                    PT100S|run-command(slow-balance-2000ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=2000
                    PT120S|run-command(fast-balance-10ms)|name=toxiproxy;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=10
                    PT120S|run-command(fast-balance-10ms)|name=alerts;proxy_name=balance-service;toxic_name=bsLatency;latency_ms=10
                        """;

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("toxiproxy");
            // TODO: make vars available to on start test?
            //commandConfig.setOnStartTest("docker exec toxiproxy /go/bin/toxiproxy-cli toxic add -n __toxic_name__ -t latency -a latency=0 __proxy_name__");
            commandConfig.setOnStartTest("docker exec toxiproxy /go/bin/toxiproxy-cli toxic add -n bsLatency -t latency -a latency=0 balance-service");
            commandConfig.setOnScheduledEvent("docker exec toxiproxy /go/bin/toxiproxy-cli toxic update -n __toxic_name__ -a latency=__latency_ms__ __proxy_name__");
            commandConfig.setOnAfterTest("docker exec toxiproxy /go/bin/toxiproxy-cli toxic remove -n __toxic_name__ __proxy_name__");
            eventConfigs.add(commandConfig);
        }

        {
            CommandRunnerEventConfig commandConfig = new CommandRunnerEventConfig();
            commandConfig.setName("alerts");
            commandConfig.setOnStartTest("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\"],\"text\":\"test start\"}' http://admin:admin@localhost:3000/api/annotations");
            commandConfig.setOnScheduledEvent("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\", \"__proxy_name__\",\"delay\"],\"text\":\"__proxy_name__ delay set to __latency_ms__ milliseconds\"}' http://admin:admin@localhost:3000/api/annotations");
            commandConfig.setOnAfterTest("curl -Ss -H \"Content-Type: application/json\" -X POST -d '{\"tags\":[\"resilience\"],\"text\":\"test end\"}' http://admin:admin@localhost:3000/api/annotations");
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
                .scheduleScript(isSlowDatabaseActive ? scheduleScriptSlowDatabase : scheduleScriptSlowBalance)
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