#!/usr/bin/env bash

# Determine stub ports based on mTLS setting
# If MTLS_ENABLED is true (case-insensitive), use HTTPS ports 31123/31124; otherwise HTTP ports 30123/30124
MTLS_FLAG_LOWER_CASE=$(printf '%s' "${MTLS_ENABLED:-}" | tr '[:upper:]' '[:lower:]')
if [ "$MTLS_FLAG_LOWER_CASE" = "true" ]; then
  ACCOUNT_PORT=31123
  BALANCE_PORT=31124
else
  ACCOUNT_PORT=30123
  BALANCE_PORT=30124
fi

# Use indexed array for portability with older Bash (e.g., macOS 3.2)
ports=("$ACCOUNT_PORT" "$BALANCE_PORT" 18080 13000)

for port in "${ports[@]}"; do
  # Get the process IDs listening on port
  pids=$(lsof -t -i:$port -sTCP:LISTEN)

  if [ -n "$pids" ]; then
    echo "Port $port - Found PIDs: $(echo $pids | tr '\n' ' ')"

    for pid in $pids; do
      # Prevent killing the script itself or its parent process
      if [ "$pid" -ne "$$" ] && [ "$pid" -ne "$PPID" ]; then
        echo "Port $port - Killing process ID $pid"
        kill "$pid"
      else
        echo "Port $port - Skipping process ID $pid (script or parent process)"
      fi
    done
  else
    echo "Port $port - No process"
  fi
done

echo "Check for hanging tiny-bank-services (in case Java process is running but not listening on a port)"
ps aux | grep "tiny-bank-service-0.0.1-SNAPSHOT.ja[r]" | awk '{print $2}' | xargs -I {} kill -9 {}

# Stop the database
cd db
docker compose down
cd - > /dev/null 2>&1

# Check if --shutdown-metrics flag is present: stop the metrics components
SHUTDOWN_METRICS=false
for arg in "$@"; do
  if [ "$arg" = "--shutdown-metrics" ]; then
    SHUTDOWN_METRICS=true
    break
  fi
done
if [ "$SHUTDOWN_METRICS" = true ]; then
  cd metrics
  docker compose down
  cd - > /dev/null 2>&1
fi
