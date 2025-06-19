# Circuit Breaker Threshold Analysis

## Overview

This document describes the methodology for testing circuit breaker behavior with a gradual delay progression around the configured timeout thresholds. The test is designed to observe how the circuit breaker opens and closes in response to varying latency patterns.

## Test Phases

The test is divided into 12 phases, each with specific delay settings designed to test different aspects of the circuit breaker:

1. **Initial Phase (10s)**: Both services at 200ms - establishes baseline performance
2. **Approach Threshold (30s)**: Increase to 500ms - approaches but doesn't exceed slow call threshold
3. **At Threshold (50s)**: Balance service at 800ms (exactly at threshold), account service at 400ms
4. **Just Above Threshold (70s)**: Balance service at 850ms (slightly above threshold)
5. **Well Above Threshold (90s)**: Balance service at 1000ms (clearly above threshold)
6. **Extreme Slowness (110s)**: Balance service at 1200ms (should trigger circuit breaker to open)
7. **Maintain Extreme (130s)**: Keep at 1200ms to observe circuit in open state
8. **Gradual Recovery (150s)**: Reduce to 700ms to test half-open state transition
9. **Further Recovery (170s)**: Reduce to 400ms to allow circuit to close
10. **Normal Operation (190s)**: Back to 100ms to observe normal operation
11. **Sudden Spike (210s)**: Quick jump to 1100ms to test rapid failure detection
12. **Quick Recovery (220s)**: Rapid return to 100ms to test recovery speed

## Relevant Circuit Breaker Settings

The test is designed around these circuit breaker parameters:

- `slidingWindowType=COUNT_BASED`: Evaluates based on a fixed number of recent calls
- `slidingWindowSize=5`: Uses 5 most recent calls for failure rate calculation
- `minimumNumberOfCalls=3`: Requires at least 3 calls before calculating failure rate
- `slowCallDurationThreshold=800`: Calls taking longer than 800ms are considered "slow"
- `slowCallRateThreshold=50`: Opens circuit when 50% of calls are slow
- `waitDurationInOpenState=1000`: Circuit stays open for 1 second before trying again
- `permittedNumberOfCallsInHalfOpenState=2`: Allows 2 test calls in half-open state

## Expected Observations

1. **Phase 1-3**: Circuit should remain closed as delays are at or below threshold
2. **Phase 4-5**: Some slow calls detected but circuit may remain closed if below threshold percentage
3. **Phase 6-7**: Circuit should open after enough slow calls accumulate
4. **Phase 8**: Circuit should transition to half-open state and permit test calls
5. **Phase 9-10**: Circuit should fully close as service returns to normal
6. **Phase 11-12**: Quick response to spike and rapid recovery demonstrates resilience

## Metrics to Monitor

- Circuit state transitions (CLOSED → OPEN → HALF-OPEN → CLOSED)
- Failure and slow call rates during each phase
- Number of fallbacks triggered
- Recovery time after service returns to normal

The results from this test can be used to fine-tune circuit breaker settings for optimal resilience.
