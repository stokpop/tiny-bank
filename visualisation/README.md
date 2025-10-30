# Visualisation: Resilience4j Circuit Breaker Model

A tiny JavaFX application that visualizes a Resilience4j CircuitBreaker as a flow of calls:
- Left service produces hollow green balls (outgoing requests); we start with more balls for a denser initial flow
- TOP pipe carries requests to the right service (simulated remote)
- A tall Circuit Breaker column spans both pipes; at this point each ball is checked by the CB
- If the CB PERMITS, the ball continues and on arrival morphs smoothly into a filled square at its slot in the right service: green on success, red on failure
- If the CB is OPEN (NOT PERMITTED), the ball smoothly morphs at the CB column into an orange square (animated inside the CB) and is then sent back via the BOTTOM pipe to the left service (it does not go to the right service)
- Squares represent incoming responses flowing back via the BOTTOM pipe to the left service

A HUD overlays the canvas showing breaker state, failure rate vs threshold, buffered calls, not-permitted count, and the current simulated failure probability. Next to the HUD, a small buffer panel visualizes the latest permitted outcomes in the sliding window: green=success, red=failure. Not-permitted (orange) calls are excluded from this buffer.

## Prerequisites
- JDK 21+
- Maven 3.9+

JavaFX artifacts are platform-specific. This module defaults to macOS aarch64 (Apple Silicon) via the `javafx.platform` property in the POM. If you are on a different platform, edit `visualisation/pom.xml` and set:

- macOS Intel: `mac`
- macOS Apple Silicon: `mac-aarch64`
- Linux x64: `linux`
- Linux aarch64: `linux-aarch64`
- Windows x64: `win`
- Windows x86: `win-x86`

Alternatively, you can run with `--add-modules` and system JavaFX, but using the Maven dependencies is simplest.

## How to run

- From repository root, build and run only this module:

```
mvn -pl visualisation -am clean package
mvn -pl visualisation -am javafx:run
```

If your Maven cannot resolve the 'javafx' prefix, use the fully-qualified goal:

```
mvn -pl visualisation -am org.openjfx:javafx-maven-plugin:0.0.8:run
```

If you prefer to run the generated jar (requires JavaFX on module path), use:

```
java -jar target/visualisation-1.0.0-SNAPSHOT.jar
```

## What you see
- Left service pre-filled with green-shaded balls (hollow, outgoing requests) and returning squares (incoming responses)
- A top pipe connecting the left service to the right service; balls flow through it
- In the right service, balls are converted into filled squares. About 10% become red squares; the rest keep their original green shade. Squares in the right service fill from the bottom row upward (front of the return pipeline), growing left→right, so buffering appears next to the bottom pipe entry.
- A bottom pipe sends squares back to the left service where squares stack from the bottom upward
- Mild jitter/wobble animation and randomized spawn intervals for a natural feel

## Architecture (refactored)
To separate model from view and group coherent responsibilities, the code is refactored into small focused classes:
- view/HudRenderer: draws the HUD and the recent outcome buffer.
- view/ServicesAndPipesRenderer: draws the two service boxes and both pipelines.
- view/CircuitBreakerRenderer: draws the tall CB column spanning both pipes.
- view/ShapeRenderer: draws primitives (hollow balls and filled squares).
- model/Outcome: represents the outcome of a call (SUCCESS, FAILURE, NOT_PERMITTED).
- config/LayoutConfig: central place for sizes and geometry helpers (prepared for further decoupling).

BallFlowApp remains as the bootstrapper, simulation loop and state holder for now to keep behavior identical. Further steps could extract the simulation state and update loop into a dedicated engine and model objects.

Enjoy!
