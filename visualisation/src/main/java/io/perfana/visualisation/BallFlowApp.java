package io.perfana.visualisation;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.perfana.visualisation.model.Outcome;
import io.perfana.visualisation.view.HudRenderer;
import io.perfana.visualisation.view.ShapeRenderer;
import io.perfana.visualisation.view.ServicesAndPipesRenderer;
import io.perfana.visualisation.view.CircuitBreakerRenderer;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.geometry.Insets;
import javafx.scene.layout.Priority;
import javafx.scene.control.Button;
import javafx.scene.control.Slider;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.SnapshotParameters;
import javafx.scene.transform.Scale;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX visualisation showing a Resilience4j CircuitBreaker model.
 * Left box creates requests (balls) going through the TOP pipe to a simulated remote service.
 * On arrival, the CircuitBreaker records success (green square) or failure (red square).
 * When the breaker is OPEN, calls are short-circuited (orange squares) and do not traverse the pipe.
 * Squares return via the BOTTOM pipe to the left box.
 * A HUD shows breaker state and failure rate over time.
 */
public class BallFlowApp extends Application {

    // View components
    private final HudRenderer hudRenderer = new HudRenderer();
    private final ServicesAndPipesRenderer servicesRenderer = new ServicesAndPipesRenderer();
    private final CircuitBreakerRenderer cbRenderer = new CircuitBreakerRenderer();
    private final ShapeRenderer shapeRenderer = new ShapeRenderer();

    private static final double WIDTH = 900;
    private static final double HEIGHT = 560; // increased to prevent left box from overlapping HUD
    private static final double CONTROL_BAR_HEIGHT = 48;

    private static final double BOX_MARGIN = 40;
    private static final double BOX_WIDTH = 220;
    private static final double BOX_HEIGHT = 300;

    private static final double PIPE_WIDTH = 80;

    private static final double BALL_RADIUS = 8;
    private static final double SQUARE_SIZE = BALL_RADIUS * 2;

    private static final Color SHORT_CIRCUIT_COLOR = Color.web("#f59e0b"); // orange

    // Position of the Circuit Breaker icon inside the top pipe (fraction from left edge of pipe)
    private static final double CB_POS_FRACTION = 0.35; // 35% into the pipe
    // Simulated connection pool capacity: max concurrent in-flight calls (after CB permitted)
    private static final int MAX_IN_FLIGHT = 6;

    private final Random random = new Random();

    // Time slider and frame history for scrubbing
    private Slider timeSlider;
    private Slider failureSlider;
    private final List<WritableImage> frameHistory = new ArrayList<>();
    private static final int MAX_FRAMES = 480; // ~16s at 30 FPS capture (with CAPTURE_EVERY_N=2)
    private static final double SNAPSHOT_SCALE = 0.6; // improve readability while keeping memory moderate
    private static final boolean IMAGE_SMOOTHING_IN_SCRUB = false; // keep text crisp when scaling up
    private static final int CAPTURE_EVERY_N = 2; // capture decimation to extend history length
    private boolean scrubbing = false;
    private boolean paused = false;
    private Button playPauseButton;
    private Button restartButton;
    private Canvas canvasRef;
    private GraphicsContext graphicsRef;

    private record Ball(double x, double y, Color color, double speed, long startMs, boolean cbChecked, boolean shortCircuited) {}
    private record Square(double x, double y, Color color, double speed, Outcome outcome, boolean occupiesSlot, Long callDurationMs) {}
    private record ShortCircuitTransition(double x, double yStart, double yEnd, double speed, long startMs, long durationMs, double currentY) {}
    private record RightBoxMorph(double xStart, double yStart, double xEnd, double yEnd, Color ballColor, Color squareColor, Outcome outcome, boolean occupiesSlot, Long callDurationMs, long startMs, long durationMs, double progress) {}

    // CircuitBreaker model
    private CircuitBreaker circuitBreaker;
    private double failureProbability = 0.2; // dynamic over time
    private long lastProbUpdateMs = 0L;
    private long shortCircuitedCount = 0L;
    private float failureRateThreshold = 50.0f;
    private int bufferVisualSize = 5; // visualize last N outcomes (align with sliding window)

    // In-flight pool tracking: increment on permit, decrement only when the returning square crosses the CB on the way back
    private int currentInFlight = 0;

    private final Deque<Outcome> recentOutcomes = new ArrayDeque<>();

    // Left box contents: balls (original) and squares (returned)
    private final Deque<Ball> leftBalls = new ArrayDeque<>();
    private final Deque<Square> leftSquares = new ArrayDeque<>();

    // Right box contents: squares (converted)
    private final Deque<Square> rightSquares = new ArrayDeque<>();

    // Pipes
    private final List<Ball> inPipeL2R = new ArrayList<>(); // top pipe: left -> right (balls before CB decision)
    private final List<Square> inPipeL2RShort = new ArrayList<>(); // top pipe: left -> right (orange short-circuited squares)
    private final List<Square> inPipeR2L = new ArrayList<>(); // bottom pipe: right -> left
    
    // Visual transition of NOT_PERMITTED: ball morphs to orange square inside CB column
    private final List<ShortCircuitTransition> shortCircuitTransitions = new ArrayList<>();

    // Right box morphs: permitted ball arriving morphs into a filled square at its grid slot
    private final List<RightBoxMorph> rightBoxMorphs = new ArrayList<>();

    // Counters
    private long totalDepartedLeft = 0L; // total number of balls that have left the left box (entered the top pipe)
    // Totals for right box (cumulative placed results)
    private long totalRightSuccess = 0L;
    private long totalRightFailure = 0L;
    // Totals for returned squares crossing CB on the way back (considered as returned)
    private long totalReturnedSuccess = 0L;
    private long totalReturnedFailure = 0L;
    private long totalReturnedNotPermitted = 0L;

    private long lastSpawnL2RNs = 0;
    private long spawnIntervalL2RNs = 500L; // 0.5s default, now in milliseconds

    private long lastSpawnR2LNs = 0;
    private long spawnIntervalR2LNs = 600L; // 0.6s default, now in milliseconds

    @Override
    public void start(Stage stage) {
        stage.setTitle("Tiny Bank - Circuit Breaker Visualisation");

        BorderPane root = new BorderPane();
        Canvas canvas = new Canvas(WIDTH, HEIGHT - CONTROL_BAR_HEIGHT);
        this.canvasRef = canvas;
        root.setCenter(canvas);

        // Controls bar at the bottom: Play/Pause, Restart, Failure slider, and Time Slider
        timeSlider = new Slider(0.0, 1.0, 1.0);
        timeSlider.setMaxWidth(Double.MAX_VALUE);
        // Track when user is dragging the slider to enter/exit scrubbing mode
        timeSlider.valueChangingProperty().addListener((obs, wasChanging, isChanging) -> scrubbing = isChanging);
        // Also handle click-to-jump without drag
        timeSlider.setOnMousePressed(e -> scrubbing = true);
        timeSlider.setOnMouseReleased(e -> scrubbing = false);

        playPauseButton = new Button("Pause");
        playPauseButton.setOnAction(e -> togglePause());

        restartButton = new Button("Restart");
        restartButton.setOnAction(e -> restartSimulation());

        // Failure probability slider (0% .. 100%)
        Label failLabel = new Label("Fail %");
        failureSlider = new Slider(0, 100, failureProbability * 100.0);
        failureSlider.setPrefWidth(160);
        failureSlider.valueProperty().addListener((obs, oldV, newV) -> {
            failureProbability = clamp(0.0, 1.0, newV.doubleValue() / 100.0);
        });

        HBox controls = new HBox(10, playPauseButton, restartButton, failLabel, failureSlider, timeSlider);
        HBox.setHgrow(timeSlider, Priority.ALWAYS);
        controls.setPadding(new Insets(8, 12, 8, 12));
        root.setBottom(controls);

        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#0f141a"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // CircuitBreaker configuration and instance
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(3))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig);
        circuitBreaker = registry.circuitBreaker("visual-cb");

        // Pre-fill left box with more balls for a denser start
        for (int i = 0; i < 80; i++) {
            leftBalls.add(createRandomBallInLeftBox());
        }

        GraphicsContext g = canvas.getGraphicsContext2D();
        this.graphicsRef = g;

        AnimationTimer timer = new AnimationTimer() {
            long lastTimeMs = 0;
            int captureTick = 0;
            @Override
            public void handle(long ignoredNow) {
                long nowMs = System.currentTimeMillis();
                if (lastTimeMs == 0) {
                    lastTimeMs = nowMs;
                    return;
                }
                double deltaSec = (nowMs - lastTimeMs) / 1000.0;
                lastTimeMs = nowMs;

                if (!scrubbing) {
                    if (!paused) {
                        // Normal live mode: update and draw the current frame
                        update(nowMs, deltaSec);
                        draw(g);
                        // Capture the canvas into the history for later scrubbing
                        try {
                            // Decimate captures to reduce memory and extend scroll-back duration
                            captureTick = (captureTick + 1) % CAPTURE_EVERY_N;
                            if (captureTick == 0) {
                                SnapshotParameters params = new SnapshotParameters();
                                params.setTransform(new Scale(SNAPSHOT_SCALE, SNAPSHOT_SCALE));
                                WritableImage snapshot = canvas.snapshot(params, null);
                                frameHistory.add(snapshot);
                                if (frameHistory.size() > MAX_FRAMES) {
                                    frameHistory.remove(0);
                                }
                            }
                        } catch (Exception ignored) {
                            // Snapshot failures should not break the animation
                        }
                    } else {
                        // Paused: no update, just keep displaying the last drawn frame
                        // Optionally redraw HUD overlays if needed
                        draw(g);
                    }
                    // Stick slider to the end while playing or paused
                    if (!timeSlider.isValueChanging()) {
                        timeSlider.setValue(1.0);
                    }
                } else {
                    // Scrubbing mode: render the selected historical frame image
                    if (!frameHistory.isEmpty()) {
                        int size = frameHistory.size();
                        double v = clamp(0.0, 1.0, timeSlider.getValue());
                        int idx = (int) Math.round(v * (size - 1));
                        idx = Math.max(0, Math.min(size - 1, idx));
                        WritableImage img = frameHistory.get(idx);
                        // Draw the historical image scaled back to canvas size, with optional smoothing control
                        boolean prevSmoothing = g.isImageSmoothing();
                        g.setImageSmoothing(IMAGE_SMOOTHING_IN_SCRUB);
                        g.drawImage(img, 0, 0, img.getWidth(), img.getHeight(), 0, 0, canvas.getWidth(), canvas.getHeight());
                        g.setImageSmoothing(prevSmoothing);
                    }
                }
            }
        };
        timer.start();
    }

    private void togglePause() {
        paused = !paused;
        if (playPauseButton != null) {
            playPauseButton.setText(paused ? "Play" : "Pause");
        }
    }

    private void restartSimulation() {
        // Clear simulation state
        inPipeL2R.clear();
        inPipeL2RShort.clear();
        inPipeR2L.clear();
        shortCircuitTransitions.clear();
        rightBoxMorphs.clear();
        leftBalls.clear();
        leftSquares.clear();
        rightSquares.clear();
        recentOutcomes.clear();
        frameHistory.clear();

        currentInFlight = 0;
        shortCircuitedCount = 0L;
        totalDepartedLeft = 0L;
        totalRightSuccess = 0L;
        totalRightFailure = 0L;
        totalReturnedSuccess = 0L;
        totalReturnedFailure = 0L;
        totalReturnedNotPermitted = 0L;
        lastSpawnL2RNs = 0L;
        lastSpawnR2LNs = 0L;
        lastProbUpdateMs = 0L;
        // Preserve user-chosen failure probability (read from slider if available)
        if (failureSlider != null) {
            failureProbability = clamp(0.0, 1.0, failureSlider.getValue() / 100.0);
        } else {
            failureProbability = clamp(0.0, 1.0, failureProbability);
        }

        // Recreate CircuitBreaker with same config
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(3))
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(cbConfig);
        circuitBreaker = registry.circuitBreaker("visual-cb");

        // Refill left box
        for (int i = 0; i < 80; i++) {
            leftBalls.add(createRandomBallInLeftBox());
        }

        // Reset controls
        if (timeSlider != null) {
            timeSlider.setValue(1.0);
        }
        paused = false;
        if (playPauseButton != null) {
            playPauseButton.setText("Pause");
        }

        // Redraw immediately to reflect reset
        if (graphicsRef != null) {
            draw(graphicsRef);
        }
    }

    private void update(long now, double deltaSec) {
        // Geometry used for movement decisions
        double leftBoxX = BOX_MARGIN;
        double rightBoxX = WIDTH - BOX_MARGIN - BOX_WIDTH;
        // Pipe geometry (must mirror draw())
        double pipeTopX = leftBoxX + BOX_WIDTH;
        double pipeTopY = HEIGHT / 2.0 - 40;
        double pipeHeight = 40;
        double pipeBottomY = HEIGHT / 2.0 + 40;
        double pipeLength = (WIDTH - BOX_MARGIN - BOX_WIDTH) - pipeTopX; // rightBoxX - pipeTopX
        double cbX = pipeTopX + CB_POS_FRACTION * pipeLength;

        // Spawn one ball from left box into the TOP pipe at intervals (left -> right)
        if (now - lastSpawnL2RNs >= spawnIntervalL2RNs && !leftBalls.isEmpty()) {
            Ball next = leftBalls.pollFirst();
            if (next != null) {
                double pipeEntryX = leftBoxX + BOX_WIDTH + (PIPE_WIDTH / 2.0);
                double pipeEntryY = pipeTopY + pipeHeight / 2.0; // exact center of top pipe
                inPipeL2R.add(new Ball(pipeEntryX, pipeEntryY, next.color, 80 + random.nextDouble() * 120, now, false, false));
                // Count ball departure from the left box
                totalDepartedLeft++;
            }
            lastSpawnL2RNs = now;
        }

        // Move balls in the top pipe to the right; perform CB decision mid-pipe; on arrival determine outcome only (defer CB record)
        List<Ball> arrivedTop = new ArrayList<>();
        List<Ball> toRemoveFromPipe = new ArrayList<>();

        for (int i = 0; i < inPipeL2R.size(); i++) {
            Ball b = inPipeL2R.get(i);
            double newX = b.x + b.speed * deltaSec;
            double newY = clampToPipe(b.y + wobble(deltaSec), pipeTopY, pipeHeight, BALL_RADIUS);
            Ball moved = new Ball(newX, newY, b.color, b.speed, b.startMs, b.cbChecked, b.shortCircuited);

            // At CB position, if not yet checked, decide permission with connection pool gating
            if (!b.cbChecked && newX >= cbX) {
                if (currentInFlight >= MAX_IN_FLIGHT) {
                    // No capacity: hold at CB icon; try again next frame
                    moved = new Ball(cbX, moved.y, moved.color, moved.speed, moved.startMs, false, false);
                } else {
                    try {
                        circuitBreaker.acquirePermission();
                        // permitted: consume a pool slot and continue as ball
                        currentInFlight++;
                        moved = new Ball(newX, moved.y, moved.color, moved.speed, moved.startMs, true, false);
                    } catch (CallNotPermittedException e) {
                        // denied: start a short transition INSIDE the CB column from top pipe center to bottom pipe center
                        shortCircuitedCount++;
                        double topCenterY = pipeTopY + pipeHeight / 2.0;
                        double bottomCenterY = pipeBottomY + pipeHeight / 2.0;
                        long durationMs = 250L; // smooth morph duration
                        shortCircuitTransitions.add(new ShortCircuitTransition(cbX, topCenterY, bottomCenterY, moved.speed, now, durationMs, topCenterY));
                        toRemoveFromPipe.add(b);
                        continue; // don't keep the ball in top pipe
                    }
                }
            }

            inPipeL2R.set(i, moved);

            if (moved.x >= rightBoxX + BOX_WIDTH / 2.0 - BALL_RADIUS) {
                arrivedTop.add(moved);
            }
        }
        if (!toRemoveFromPipe.isEmpty()) {
            inPipeL2R.removeAll(toRemoveFromPipe);
        }

        if (!arrivedTop.isEmpty()) {
            inPipeL2R.removeAll(arrivedTop);
            for (Ball b : arrivedTop) {
                long durationMs = now - b.startMs;
                // Only permitted balls reach here; simulate remote call outcome but DO NOT record in CB yet
                boolean failed = random.nextDouble() < failureProbability;
                Outcome out;
                Color squareColor;
                if (failed) {
                    out = Outcome.FAILURE;
                    squareColor = Color.web("#ef4444");
                } else {
                    out = Outcome.SUCCESS;
                    squareColor = b.color;
                }
                // Create a morph animation that happens just inside the right box, in front of the BOTTOM pipe mouth
                // (was top pipe). This makes the square move towards the lower tube for a smoother, more natural
                // animation towards the return path.
                double rightBoxY = (HEIGHT - BOX_HEIGHT) / 2.0;
                double targetX = rightBoxX + BALL_RADIUS + 6; // just inside the right box edge (in front of tube)
                double targetY = pipeBottomY + pipeHeight / 2.0; // align with bottom pipe centerline

                double startX = Math.min(b.x, rightBoxX + BOX_WIDTH - BALL_RADIUS - 6); // keep inside box edge
                double startY = Math.min(Math.max(b.y, rightBoxY + BALL_RADIUS + 6), rightBoxY + BOX_HEIGHT - BALL_RADIUS - 6);
                long morphDuration = 240L;
                rightBoxMorphs.add(new RightBoxMorph(startX, startY, targetX, targetY, b.color, squareColor, out, true, durationMs, now, morphDuration, 0.0));
            }
        }

        // Move short-circuited squares along the top pipe to the right (legacy path if any remain)
        List<Square> arrivedShort = new ArrayList<>();
        for (int i = 0; i < inPipeL2RShort.size(); i++) {
            Square s = inPipeL2RShort.get(i);
            double newX = s.x + s.speed * deltaSec;
            double newY = clampToPipe(s.y + wobble(deltaSec), pipeTopY, pipeHeight, SQUARE_SIZE / 2.0);
            Square moved = new Square(newX, newY, s.color, s.speed, s.outcome, s.occupiesSlot, null);
            inPipeL2RShort.set(i, moved);
            if (newX >= rightBoxX + BOX_WIDTH / 2.0 - BALL_RADIUS) {
                arrivedShort.add(moved);
            }
        }
        if (!arrivedShort.isEmpty()) {
            inPipeL2RShort.removeAll(arrivedShort);
            // deposit orange squares into right box
            for (Square s : arrivedShort) {
                rightSquares.add(new Square(0, 0, SHORT_CIRCUIT_COLOR, 0, Outcome.NOT_PERMITTED, false, null));
            }
        }

        // Advance short-circuit transitions inside the CB and inject into bottom pipe when done
        if (!shortCircuitTransitions.isEmpty()) {
            List<ShortCircuitTransition> finished = new ArrayList<>();
            for (int i = 0; i < shortCircuitTransitions.size(); i++) {
                ShortCircuitTransition t = shortCircuitTransitions.get(i);
                double elapsed = now - t.startMs;
                double progress = clamp(0.0, 1.0, elapsed / (double) t.durationMs);
                double y = t.yStart + (t.yEnd - t.yStart) * progress;
                shortCircuitTransitions.set(i, new ShortCircuitTransition(t.x, t.yStart, t.yEnd, t.speed, t.startMs, t.durationMs, y));
                if (progress >= 1.0) {
                    finished.add(shortCircuitTransitions.get(i));
                }
            }
            if (!finished.isEmpty()) {
                for (ShortCircuitTransition t : finished) {
                    // inject orange square into bottom pipe at CB X and bottom center Y, preserving speed
                    inPipeR2L.add(new Square(t.x, t.yEnd, SHORT_CIRCUIT_COLOR, t.speed, Outcome.NOT_PERMITTED, false, null));
                }
                shortCircuitTransitions.removeAll(finished);
            }
        }

        // Advance right-box morphs and finalize into rightSquares when completed
        if (!rightBoxMorphs.isEmpty()) {
            List<RightBoxMorph> done = new ArrayList<>();
            for (int i = 0; i < rightBoxMorphs.size(); i++) {
                RightBoxMorph m = rightBoxMorphs.get(i);
                double elapsed = now - m.startMs;
                double p = clamp(0.0, 1.0, elapsed / (double) m.durationMs);
                rightBoxMorphs.set(i, new RightBoxMorph(m.xStart, m.yStart, m.xEnd, m.yEnd, m.ballColor, m.squareColor, m.outcome, m.occupiesSlot, m.callDurationMs, m.startMs, m.durationMs, p));
                if (p >= 1.0) {
                    done.add(rightBoxMorphs.get(i));
                }
            }
            if (!done.isEmpty()) {
                for (RightBoxMorph m : done) {
                    rightSquares.add(new Square(0, 0, m.squareColor, 0, m.outcome, m.occupiesSlot, m.callDurationMs));
                    // Count totals for right box when the morph completes (square is placed)
                    if (m.outcome == Outcome.SUCCESS) totalRightSuccess++;
                    else if (m.outcome == Outcome.FAILURE) totalRightFailure++;
                }
                rightBoxMorphs.removeAll(done);
            }
        }

        // Spawn one square from right box into the BOTTOM pipe at intervals (right -> left)
        if (now - lastSpawnR2LNs >= spawnIntervalR2LNs && !rightSquares.isEmpty()) {
            Square nextSq = rightSquares.pollFirst();
            if (nextSq != null) {
                double pipeEntryX = rightBoxX - (SQUARE_SIZE / 2.0) - 6; // start more to the right inside the pipe
                double pipeEntryY = pipeBottomY + pipeHeight / 2.0; // exact center of bottom pipe
                inPipeR2L.add(new Square(pipeEntryX, pipeEntryY, nextSq.color, 80 + random.nextDouble() * 120, nextSq.outcome, nextSq.occupiesSlot, nextSq.callDurationMs));
            }
            lastSpawnR2LNs = now;
        }

        // Move squares in the bottom pipe to the left; on arrival put into left box (as squares)
        List<Square> arrivedBottom = new ArrayList<>();
        for (int i = 0; i < inPipeR2L.size(); i++) {
            Square s = inPipeR2L.get(i);
            double newX = s.x - s.speed * deltaSec; // moving leftwards
            double newY = clampToPipe(s.y + wobble(deltaSec), pipeBottomY, pipeHeight, SQUARE_SIZE / 2.0);

            // When passing the CB column on the right side, update the buffer with this call's outcome once
            // Count crossing when moving left past CB column; include equality to handle injected short-circuits at cbX
            boolean crossedCb = s.x >= cbX && newX < cbX;
            Outcome outcomeForBuffer = s.outcome;
            boolean occupies = s.occupiesSlot;
            if (crossedCb) {
                if (outcomeForBuffer != null) {
                    addOutcome(outcomeForBuffer);
                    // Also update returned totals at the moment of crossing the CB on the way back
                    if (outcomeForBuffer == Outcome.SUCCESS) totalReturnedSuccess++;
                    else if (outcomeForBuffer == Outcome.FAILURE) totalReturnedFailure++;
                    else if (outcomeForBuffer == Outcome.NOT_PERMITTED) totalReturnedNotPermitted++;
                    // clear outcome after accounting, so it won't be counted again
                    outcomeForBuffer = null;
                }
                // Reduce in-flight only when the returning square reaches back to the CB
                if (occupies) {
                    currentInFlight = Math.max(0, currentInFlight - 1);
                    occupies = false; // prevent double-decrement
                }
                // Register the outcome into the CB only now (when crossing CB on return)
                if (s.callDurationMs != null) {
                    if (s.outcome == Outcome.FAILURE) {
                        circuitBreaker.onError(s.callDurationMs, TimeUnit.MILLISECONDS, new RuntimeException("simulated-failure"));
                    } else if (s.outcome == Outcome.SUCCESS) {
                        circuitBreaker.onSuccess(s.callDurationMs, TimeUnit.MILLISECONDS);
                    }
                }
            }

            // Clear duration once we have reported to CB to avoid duplicate reporting
            Long nextDuration = crossedCb ? null : s.callDurationMs;
            Square moved = new Square(newX, newY, s.color, s.speed, outcomeForBuffer, occupies, nextDuration);
            inPipeR2L.set(i, moved);

            // Stop a bit earlier before entering the left box: at the start of the bottom pipe plus small margin
            double pipeBottomX = leftBoxX + BOX_WIDTH;
            if (newX <= pipeBottomX + (SQUARE_SIZE / 2.0) + 6) {
                arrivedBottom.add(moved);
            }
        }
        if (!arrivedBottom.isEmpty()) {
            inPipeR2L.removeAll(arrivedBottom);
            leftSquares.addAll(arrivedBottom.stream().map(s -> new Square(0, 0, s.color, 0, null, false, null)).toList());
        }

        // Gentle randomization of spawn intervals to make flow less uniform
        if (random.nextDouble() < 0.01) {
            spawnIntervalL2RNs = (long) (300L + random.nextDouble() * 600L); // 300–900 ms
        }
        if (random.nextDouble() < 0.01) {
            spawnIntervalR2LNs = (long) (300L + random.nextDouble() * 600L); // 300–900 ms
        }

        // User-controlled failure probability via slider: no automatic oscillation
    }

    private double wobble(double deltaSec) {
        return (random.nextDouble() - 0.5) * 10 * deltaSec * 60; // small vertical jitter
    }

    private double clampToPipe(double y, double pipeY, double pipeHeight, double halfSize) {
        double minY = pipeY + halfSize + 2; // small padding from pipe border
        double maxY = pipeY + pipeHeight - halfSize - 2;
        if (y < minY) return minY;
        if (y > maxY) return maxY;
        return y;
    }

    private void draw(GraphicsContext g) {
        // Clear background
        g.setFill(Color.web("#0f141a"));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Coordinates
        double leftBoxX = BOX_MARGIN;
        double leftBoxY = (HEIGHT - BOX_HEIGHT) / 2.0;

        double rightBoxX = WIDTH - BOX_MARGIN - BOX_WIDTH;
        double rightBoxY = leftBoxY;

        // Top pipe (left -> right)
        double pipeTopX = leftBoxX + BOX_WIDTH;
        double pipeTopY = HEIGHT / 2.0 - 40;
        double pipeHeight = 40;
        double pipeLength = rightBoxX - pipeTopX;

        // Bottom pipe (right -> left)
        double pipeBottomX = pipeTopX;
        double pipeBottomY = HEIGHT / 2.0 + 40;

        // Draw service boxes and both pipes
        servicesRenderer.drawBoxesAndPipes(g,
                leftBoxX, leftBoxY,
                rightBoxX, rightBoxY,
                pipeTopX, pipeTopY,
                pipeBottomY, pipeLength, pipeHeight,
                BOX_WIDTH, BOX_HEIGHT);

        // Draw contents in boxes
        drawMixedInBox(g, leftBoxX, leftBoxY, leftBalls, leftSquares);
        // Draw waiting replies (squares) queued at the mouth of the LOWER pipe, not at the bottom grid
        drawSquaresInBox(g, rightBoxX, rightBoxY, rightSquares, pipeBottomY, pipeHeight);
        // Draw right-box morphs on top for visibility
        drawRightBoxMorphs(g, rightBoxX, rightBoxY);

        // Draw moving shapes in pipes
        for (Ball b : inPipeL2R) {
            drawBall(g, b.x, b.y, b.color);
        }
        for (Square s : inPipeL2RShort) {
            drawSquare(g, s.x, s.y, s.color);
        }
        for (Square s : inPipeR2L) {
            drawSquare(g, s.x, s.y, s.color);
        }

        // Draw Circuit Breaker icon spanning both pipes
        drawCircuitBreakerIcon(g, pipeTopX, pipeTopY, pipeBottomY, pipeLength, pipeHeight);

        // Draw transitions inside the CB column (orange squares moving vertically)
        for (ShortCircuitTransition t : shortCircuitTransitions) {
            drawSquare(g, t.x, t.currentY, SHORT_CIRCUIT_COLOR);
        }

        // HUD overlay with CircuitBreaker state and failure rate
        hudRenderer.draw(g, circuitBreaker, currentInFlight, MAX_IN_FLIGHT, failureProbability, recentOutcomes, bufferVisualSize);

        // Draw counters near boxes
        drawBoxCounters(g, leftBoxX, leftBoxY, rightBoxX, rightBoxY);
    }

    private void drawCircuitBreakerIcon(GraphicsContext g, double pipeTopX, double pipeTopY, double pipeBottomY, double pipeLength, double pipeHeight) {
        double cbX = pipeTopX + CB_POS_FRACTION * pipeLength;
        cbRenderer.drawColumn(g, circuitBreaker, cbX, pipeTopY, pipeBottomY, pipeHeight);
    }

    private void addOutcome(Outcome outcome) {
        // Exclude NOT_PERMITTED from the visual failure buffer
        if (outcome == Outcome.NOT_PERMITTED) {
            return;
        }
        recentOutcomes.addLast(outcome);
        while (recentOutcomes.size() > bufferVisualSize) {
            recentOutcomes.removeFirst();
        }
    }

    private void drawMixedInBox(GraphicsContext g, double boxX, double boxY, Deque<Ball> balls, Deque<Square> squares) {
        // Grid dimensions
        int cols = (int) Math.max(1, Math.floor((BOX_WIDTH - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));
        int rows = (int) Math.max(1, Math.floor((BOX_HEIGHT - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));

        // 1) Draw balls from the TOP, left-to-right, top-to-bottom (as-is)
        int ballIndex = 0;
        for (Ball b : balls) {
            int row = ballIndex / cols;
            int col = ballIndex % cols;
            if (row >= rows) {
                // No more space; stop drawing anything else in this box
                return;
            }
            double x = boxX + BALL_RADIUS + 6 + col * (BALL_RADIUS * 2 + 4);
            double y = boxY + BALL_RADIUS + 6 + row * (BALL_RADIUS * 2 + 4);
            drawBall(g, x, y, b.color);
            ballIndex++;
        }

        // Number of rows occupied by balls
        int ballRows = (int) Math.ceil(ballIndex / (double) cols);
        if (ballRows > rows) ballRows = rows;

        // 2) Draw returned squares from the BOTTOM upwards, without overlapping ball rows
        int availableRowsForSquares = rows - ballRows;
        if (availableRowsForSquares <= 0) {
            return; // no vertical space below the balls
        }
        int capacitySquares = availableRowsForSquares * cols;

        int squareIndex = 0;
        for (Square s : squares) {
            if (squareIndex >= capacitySquares) break; // no more space for squares

            int rowFromBottom = squareIndex / cols; // 0 = bottom row, then upwards
            int row = rows - 1 - rowFromBottom;      // actual row index from top
            int col = squareIndex % cols;

            double x = boxX + BALL_RADIUS + 6 + col * (BALL_RADIUS * 2 + 4);
            double y = boxY + BALL_RADIUS + 6 + row * (BALL_RADIUS * 2 + 4);
            drawSquare(g, x, y, s.color);

            squareIndex++;
        }
    }

    private void drawSquaresInBox(GraphicsContext g,
                                   double boxX,
                                   double boxY,
                                   Deque<Square> squares,
                                   double pipeBottomY,
                                   double pipeHeight) {
        // Visualise queued replies right in front of the LOWER pipe mouth, in a single horizontal queue
        // along the pipe centerline. Do NOT stack/wrap vertically; extend horizontally inside the right box.

        if (squares.isEmpty()) return;

        // Queue layout parameters
        double gap = 4.0;
        double mouthXInside = boxX + BALL_RADIUS + 6; // left inner edge of the right box (pipe mouth)
        double yCenter = pipeBottomY + pipeHeight / 2.0; // align with bottom pipe centerline

        // Right inner bound (keep drawings inside the right box visual area)
        double maxX = boxX + BOX_WIDTH - BALL_RADIUS - 6;

        int index = 0;
        for (Square s : squares) {
            double x = mouthXInside + index * (SQUARE_SIZE + gap);
            if (x > maxX) {
                // Stop drawing beyond the right inner bound; queue remains a single line
                break;
            }
            double y = yCenter;
            drawSquare(g, x, y, s.color);
            index++;
        }
    }

    private void drawRightBoxMorphs(GraphicsContext g, double rightBoxX, double rightBoxY) {
        if (rightBoxMorphs.isEmpty()) return;
        for (RightBoxMorph m : rightBoxMorphs) {
            double p = clamp(0.0, 1.0, m.progress);
            // Position interpolation
            double x = m.xStart + (m.xEnd - m.xStart) * p;
            double y = m.yStart + (m.yEnd - m.yStart) * p;

            // Cross-fade from hollow ball to filled square
            double ballAlpha = 1.0 - p;
            double squareAlpha = p;

            // Draw ghost path line subtly (optional): skipped for minimalism

            // Draw ball outline with fading alpha
            g.setGlobalAlpha(ballAlpha);
            drawBall(g, x, y, m.ballColor);

            // Draw filling square with increasing alpha and slight corner rounding for a softer morph
            g.setGlobalAlpha(squareAlpha);
            drawSquare(g, x, y, m.squareColor);

            // Reset alpha
            g.setGlobalAlpha(1.0);
        }
    }

    private void drawBall(GraphicsContext g, double x, double y, Color color) {
        shapeRenderer.drawBall(g, x, y, color);
    }

    private void drawSquare(GraphicsContext g, double x, double y, Color color) {
        shapeRenderer.drawSquare(g, x, y, color);
    }

    private void drawBoxCounters(GraphicsContext g, double leftBoxX, double leftBoxY, double rightBoxX, double rightBoxY) {
        // Left box: total departed counter
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("Departed: " + totalDepartedLeft, leftBoxX, Math.max(14, leftBoxY - 8));

        // Right box: cumulative totals (success/failure) placed in the grid
        double rx = rightBoxX;
        double ry = Math.max(14, rightBoxY - 8);
        g.fillText(String.format("Right box total — Success: %d  Failure: %d", totalRightSuccess, totalRightFailure), rx, ry);

        // Left box bottom: totals of returned squares that crossed the CB on the way back
        double lbx = leftBoxX;
        double lby = leftBoxY + BOX_HEIGHT + 16;
        g.fillText(String.format("Returned — Success: %d  Failure: %d  Not permitted: %d",
                totalReturnedSuccess, totalReturnedFailure, totalReturnedNotPermitted), lbx, lby);
    }

    private void drawHud(GraphicsContext g) {
        double x = 20, y = 18;
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("CircuitBreaker: " + circuitBreaker.getState(), x, y);

        var metrics = circuitBreaker.getMetrics();
        float failureRate = metrics.getFailureRate();
        float buffered = metrics.getNumberOfBufferedCalls();
        float notPermitted = metrics.getNumberOfNotPermittedCalls();
        float slowCallRate = metrics.getSlowCallRate();

        g.fillText(String.format("Failure rate: %.1f%% (threshold %.0f%%)", failureRate, failureRateThreshold), x, y + 16);
        g.fillText(String.format("Buffered calls: %.0f  Not permitted: %.0f  Slow rate: %.1f%%", buffered, notPermitted, slowCallRate), x, y + 32);
        g.fillText(String.format("In-flight (pool): %d / %d", currentInFlight, MAX_IN_FLIGHT), x, y + 48);
        g.fillText(String.format("Failure probability (sim): %.0f%%", failureProbability * 100.0), x, y + 64);

        // Bar showing failure rate vs threshold
        double barX = x;
        double barY = y + 76;
        double barW = 220;
        double barH = 10;
        g.setFill(Color.color(1,1,1,0.15));
        g.fillRect(barX, barY, barW, barH);
        double frac = clamp(0, 1, failureRate / 100.0);
        Color barColor = failureRate >= failureRateThreshold ? Color.web("#ef4444") : Color.web("#22c55e");
        g.setFill(barColor);
        g.fillRect(barX, barY, barW * frac, barH);
    }

    private static double clamp(double min, double max, double v) {
        return Math.max(min, Math.min(max, v));
    }

    private Ball createRandomBallInLeftBox() {
        // Generate only shades of green: restrict hue to the green band (~100°–180°)
        double hueDeg = 100 + random.nextDouble() * 80; // [100, 180)
        double saturation = 0.65 + random.nextDouble() * 0.3; // [0.65, 0.95)
        double brightness = 0.80 + random.nextDouble() * 0.2; // [0.80, 1.0)
        Color color = Color.hsb(hueDeg, saturation, brightness);
        return new Ball(0, 0, color, 0, System.currentTimeMillis(), false, false);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
