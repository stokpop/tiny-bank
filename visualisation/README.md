# Visualisation: Balls and Squares Through Two Pipes

A tiny JavaFX application that shows a canvas with small balls in a left box flowing one-by-one through a TOP pipe into a right box. In the right box, balls are transformed into squares. Squares then travel back through a BOTTOM pipe to the left box.

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
- Left box pre-filled with colored balls (and returning squares)
- A top pipe connecting the left box to the right box; balls flow through it
- In the right box, balls are converted into squares
- A bottom pipe sends squares back to the left box
- Mild jitter/wobble animation and randomized spawn intervals for a natural feel

Enjoy!
