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

# Check if toxiproxy-cli is installed
if ! command -v toxiproxy-cli > /dev/null 2>&1; then
  echo "Error: toxiproxy-cli is not installed. Please install toxiproxy-cli and try again."
  exit 1
fi

# Check if k6 is installed
if ! command -v k6 > /dev/null 2>&1; then
  echo "Error: k6 is not installed. Please install k6 and try again." 
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
toxiproxy-cli -host localhost:8474 create -l 0.0.0.0:15432 -u postgres-tiny-bank:5432 test-postgres || { echo "Error: Failed to create postgres proxy"; exit 1; }
toxiproxy-cli -host localhost:8474 create -l 0.0.0.0:20123 -u host.docker.internal:30123 account-service || { echo "Error: Failed to create account service proxy"; exit 1; }
toxiproxy-cli -host localhost:8474 create -l 0.0.0.0:20124 -u host.docker.internal:30124 balance-service || { echo "Error: Failed to create balance service proxy"; exit 1; }

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
  #OTEL_TO_CONSOLE="-Dotel.metrics.exporter=console -Dotel.traces.exporter=console -Dotel.logs.exporter=console"

  # these do not seem to get passed along to the otel collector
  RESOURCE_ATTRIBUTES="-Dotel.resource.attributes=service.name=tiny-bank-service,system_under_test=tiny-bank,test_environment=silver,service=tiny-bank-service"

  # workaround for the resource attributes not being exported via the otel collector attributes processor, needs "include_metadata: true"
  OTLP_HEADERS="-Dotel.exporter.otlp.headers=system_under_test=tiny-bank,test_environment=silver,service=tiny-bank-service"

  # do not sent complete process information
  DISABLE_PROCESS_RESOURCE_PROVIDER="-Dotel.java.disabled.resource.providers=io.opentelemetry.instrumentation.resources.ProcessResourceProvider"

  #TRACES_CONFIG="-Dotel.traces.exporter=none"
  TRACES_CONFIG="-Dotel.traces.exporter=otlp -Dotel.exporter.otlp.protocol=grpc -Dotel.exporter.otlp.endpoint=http://localhost:4317"
  LOGS_CONFIG="-Dotel.logs.exporter=none"

  #ENABLE_MICROMETER="-Dotel.instrumentation.micrometer.enabled=true"

  EXPORT_INTERVAL="-Dotel.metric.export.interval=5000"

  OTEL_AGENT="-javaagent:opentelemetry-javaagent.jar $ENABLE_MICROMETER $TRACES_CONFIG $LOGS_CONFIG $OTEL_TO_CONSOLE $RESOURCE_ATTRIBUTES $OTLP_HEADERS $DISABLE_PROCESS_RESOURCE_PROVIDER $EXPORT_INTERVAL"

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

# Determine stub ports based on mTLS setting
# If MTLS_ENABLED is true (case-insensitive), check HTTPS ports 31123/31124; otherwise HTTP ports 30123/30124
MTLS_FLAG_LOWER_CASE=$(printf '%s' "${MTLS_ENABLED:-}" | tr '[:upper:]' '[:lower:]')
if [ "$MTLS_FLAG_LOWER_CASE" = "true" ]; then
  ACCOUNT_PORT=31123
  BALANCE_PORT=31124
else
  ACCOUNT_PORT=30123
  BALANCE_PORT=30124
fi

# Define ports and names using indexed arrays (compatible with Bash 3.2)
ports=(18080 13000 3000 "$ACCOUNT_PORT" "$BALANCE_PORT")
names=("tiny-bank-service" "tiny-fe" "Grafana" "Account stub" "Balance stub")

for i in "${!ports[@]}"; do
  port="${ports[$i]}"
  name="${names[$i]}"
  if ! nc -z localhost "$port" >/dev/null 2>&1; then
    echo "Error: $name is not running. Please check the logs and try again."
    if [ "$MTLS_FLAG_LOWER_CASE" = "true" ]; then
      echo "Hint: mTLS appears enabled (MTLS_ENABLED=true). Account/Balance stubs should listen on 31123/31124."
    else
      echo "Hint: mTLS appears disabled. Account/Balance stubs should listen on 30123/30124."
    fi
    exit 1
  fi
done

echo "All services are up and running."