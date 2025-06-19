#!/bin/bash

check_flag() {
  local flag="$1"
  shift
  for arg in "$@"; do
    if [ "$arg" = "$flag" ]; then
      return 0
    fi
  done
  return 1
}

DEBUG=false

# Check if --debug flag is provided
if check_flag "--debug" "$@"; then
  DEBUG=true
fi

# Set up output redirection based on the --debug flag
if [ "$DEBUG" = true ]; then
  OUT="/dev/stdout"
  ERR="/dev/stderr"
else
  OUT="/dev/null"
  ERR="/dev/null"
fi

# Check if Docker is installed
if ! command -v docker > /dev/null 2>&1; then
  echo "Error: Docker is not installed. Please install Docker and try again."
  exit 1
fi

# Verify if Docker is running
if ! docker ps > /dev/null 2>&1; then
  echo "Error: Docker is not running. Please start Docker and try again."
  exit 1
fi

# Check if Java is installed
if ! command -v java > /dev/null 2>&1; then
  echo "Error: Java is not installed. Please install Java 21+ and try again."
  exit 1
fi

# Check if x2i, jfr-exporter.jar and opentelemetry-agent.jar are available
if [ ! -f x2i ] || [ ! -f jfr-exporter.jar ] || [ ! -f opentelemetry-javaagent.jar ]; then
  echo "Downloading x2i, jfr-exporter.jar and opentelemetry-agent.jar."
  ./download-components.sh
fi

# Check if java version is 21+
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 21 ]]; then
  echo "Error: Java version 21+ is required, found: $JAVA_VERSION. Please install Java 21+ and try again."
  exit 1
else
  echo "Java version: $JAVA_VERSION"
fi

echo "Stopping any running components"
./stop-test.sh

echo "Building app"
./mvnw package -DskipTests > $OUT 2>$ERR

echo "Starting stubs"
./create-wiremock-jars.sh > $OUT 2>$ERR
java -jar service/target/tiny-bank-service-0.0.1-account-stub-SNAPSHOT.jar > $OUT 2>$ERR &
java -jar service/target/tiny-bank-service-0.0.1-balance-stub-SNAPSHOT.jar > $OUT 2>$ERR &

echo "Starting database"
cd db
docker compose up -d || { echo "Error: Failed to start database using Docker Compose."; exit 1; }
cd - > $OUT 2>$ERR

echo "Init toxiproxy"
docker exec toxiproxy /go/bin/toxiproxy-cli create -l 0.0.0.0:15432 -u postgres-tiny-bank:5432 test-postgres || { echo "Error: Failed to create postgres proxy"; exit 1; }
docker exec toxiproxy /go/bin/toxiproxy-cli create -l 0.0.0.0:20123 -u host.docker.internal:30123 account-service || { echo "Error: Failed to create account service proxy"; exit 1; }
docker exec toxiproxy /go/bin/toxiproxy-cli create -l 0.0.0.0:20124 -u host.docker.internal:30124 balance-service || { echo "Error: Failed to create balance service proxy"; exit 1; }

echo "Waiting for database to start"
sleep 3

echo "Starting and provisioning Influxdb and Grafana"
cd metrics
docker compose up -d || { echo "Error: Failed to start Grafana using Docker Compose."; exit 1; }
cd - > $OUT 2>$ERR

echo "Starting services"

if check_flag "--jfr-agent" "$@"; then
  INFLUX_URL=${INFLUX_URL:-http://localhost:8086}
  INFLUX_DB_JFR=${INFLUX_DB_JFR:-jfr}
  JFR_AGENT="-javaagent:jfr-exporter.jar=influxUrl=$INFLUX_URL,influxDatabase=$INFLUX_DB_JFR,tag=systemUnderTest/tiny-bank,tag=service/tiny-bank-service,tag=testEnvironment/silver -XX:NativeMemoryTracking=summary"
else
  JFR_AGENT=""
fi

if check_flag "--otel-agent" "$@"; then
  # for debug to console only
  # OTEL_TO_CONSOLE="-Dotel.metrics.exporter=console -Dotel.traces.exporter=console -Dotel.logs.exporter=console"

  # these do not seem to get passed along to the otel collector
  RESOURCE_ATTRIBUTES="-Dotel.resource.attributes=service.name=tiny-bank-service,system_under_test=tiny-bank,test_environment=silver,service=tiny-bank-service"

  # workaround for the resource attributes not being exported via the otel collector attributes processor, needs "include_metadata: true"
  OTLP_HEADERS="-Dotel.exporter.otlp.headers=system_under_test=tiny-bank,test_environment=silver,service=tiny-bank-service"

  # do not sent complete process information
  DISABLE_PROCESS_RESOURCE_PROVIDER="-Dotel.java.disabled.resource.providers=io.opentelemetry.instrumentation.resources.ProcessResourceProvider"

  DISABLE_TRACES="-Dotel.traces.exporter=none"
  DISABLE_LOGS="-Dotel.logs.exporter=none"

  #ENABLE_MICROMETER="-Dotel.instrumentation.micrometer.enabled=true"

  EXPORT_INTERVAL="-Dotel.metric.export.interval=5000"

  OTEL_AGENT="-javaagent:opentelemetry-javaagent.jar $ENABLE_MICROMETER $DISABLE_TRACES $DISABLE_LOGS $OTEL_TO_CONSOLE $RESOURCE_ATTRIBUTES $OTLP_HEADERS $DISABLE_PROCESS_RESOURCE_PROVIDER $EXPORT_INTERVAL"

else
  OTEL_AGENT=""
fi

JAVA_OPTIONS="-Xmx512m"

mkdir -p logs
java $JFR_AGENT $OTEL_AGENT $JAVA_OPTIONS -jar service/target/tiny-bank-service-0.0.1-SNAPSHOT.jar >logs/tiny-bank-service.log 2>logs/tiny-bank-service.log &

echo "Starting tiny-fe"
cd app/tiny-fe
npm install > $OUT 2>$ERR
node app.js > $OUT 2>$ERR &
cd - > $OUT 2>$ERR

echo "Open the tiny-bank app at http://localhost:13000 and use the given user ids"
echo "Open the Grafana at http://localhost:3000 and login with admin/admin"

sleep 6

declare services=(
  [18080]="tiny-bank-service"
  [13000]="tiny-fe"
  [3000]="Grafana"
  [30123]="Account stub"
  [30124]="Balance stub"
)

for port in "${!services[@]}"; do
  if ! nc -z localhost "$port"; then
    echo "Error: ${services[$port]} is not running. Please check the logs and try again."
    exit 1
  fi
done

echo "All services are up and running."