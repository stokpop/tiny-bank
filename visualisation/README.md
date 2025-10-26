# Visualisation: Resilience4j Circuit Breaker Model

A tiny JavaFX application that visualizes a Resilience4j CircuitBreaker as a flow of calls:
- Left box produces hollow green balls (requests); we start with more balls for a denser initial flow
- TOP pipe carries calls to the right box (simulated remote service)
- A tall Circuit Breaker column spans both pipes; at this point each ball is checked by the CB
- If the CB PERMITS, the ball continues and on arrival becomes a filled square: green on success, red on failure
- If the CB is OPEN (NOT PERMITTED), the ball is converted at the icon into an orange square and is immediately sent back via the BOTTOM pipe to the left box (it does not go to the right box)
- Squares flow back via the BOTTOM pipe to the left box

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
- Left box pre-filled with green-shaded balls (hollow) and returning squares
- A top pipe connecting the left box to the right box; balls flow through it
- In the right box, balls are converted into filled squares. About 10% become red squares; the rest keep their original green shade
- A bottom pipe sends squares back to the left box where squares stack from the bottom upward
- Mild jitter/wobble animation and randomized spawn intervals for a natural feel

Enjoy!
