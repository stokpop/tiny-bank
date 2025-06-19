#!/bin/bash

echo "Starting circuit breaker test with gradual delay progression"
echo "This test will run for approximately 4 minutes"

# Run the circuit breaker test scheduler
java -cp target/auto-resilience-test-1.0-SNAPSHOT.jar io.perfana.scheduler.CircuitBreakerTestScheduler

echo "Test completed."

