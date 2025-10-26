package io.perfana.visualisation;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.BorderPane;
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

    private static final double WIDTH = 900;
    private static final double HEIGHT = 440;

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

    private record Ball(double x, double y, Color color, double speed, long startMs, boolean cbChecked, boolean shortCircuited) {}
    private record Square(double x, double y, Color color, double speed) {}

    // CircuitBreaker model
    private CircuitBreaker circuitBreaker;
    private double failureProbability = 0.2; // dynamic over time
    private long lastProbUpdateMs = 0L;
    private long shortCircuitedCount = 0L;
    private float failureRateThreshold = 50.0f;
    private int bufferVisualSize = 5; // visualize last N outcomes (align with sliding window)

    private enum Outcome { SUCCESS, FAILURE, NOT_PERMITTED }
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

    private long lastSpawnL2RNs = 0;
    private long spawnIntervalL2RNs = 500L; // 0.5s default, now in milliseconds

    private long lastSpawnR2LNs = 0;
    private long spawnIntervalR2LNs = 600L; // 0.6s default, now in milliseconds

    @Override
    public void start(Stage stage) {
        stage.setTitle("Tiny Bank - Circuit Breaker Visualisation");

        BorderPane root = new BorderPane();
        Canvas canvas = new Canvas(WIDTH, HEIGHT);
        root.setCenter(canvas);

        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#0f141a"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // CircuitBreaker configuration and instance
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(10)
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

        AnimationTimer timer = new AnimationTimer() {
            long lastTimeMs = 0;
            @Override
            public void handle(long ignoredNow) {
                long nowMs = System.currentTimeMillis();
                if (lastTimeMs == 0) {
                    lastTimeMs = nowMs;
                    return;
                }
                double deltaSec = (nowMs - lastTimeMs) / 1000.0;
                lastTimeMs = nowMs;

                update(nowMs, deltaSec);
                draw(g);
            }
        };
        timer.start();
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
            }
            lastSpawnL2RNs = now;
        }

        // Move balls in the top pipe to the right; perform CB decision mid-pipe; on arrival apply outcome
        List<Ball> arrivedTop = new ArrayList<>();
        List<Ball> toRemoveFromPipe = new ArrayList<>();

        for (int i = 0; i < inPipeL2R.size(); i++) {
            Ball b = inPipeL2R.get(i);
            double newX = b.x + b.speed * deltaSec;
            double newY = clampToPipe(b.y + wobble(deltaSec), pipeTopY, pipeHeight, BALL_RADIUS);
            Ball moved = new Ball(newX, newY, b.color, b.speed, b.startMs, b.cbChecked, b.shortCircuited);

            // At CB position, if not yet checked, decide permission with connection pool gating
            if (!b.cbChecked && newX >= cbX) {
                int inFlight = (int) inPipeL2R.stream().filter(bb -> bb.cbChecked).count();
                if (inFlight >= MAX_IN_FLIGHT) {
                    // No capacity: hold at CB icon; try again next frame
                    moved = new Ball(cbX, moved.y, moved.color, moved.speed, moved.startMs, false, false);
                } else {
                    try {
                        circuitBreaker.acquirePermission();
                        // permitted: mark as checked and continue as ball
                        moved = new Ball(newX, moved.y, moved.color, moved.speed, moved.startMs, true, false);
                    } catch (CallNotPermittedException e) {
                        // denied: convert to orange square at CB and send back immediately via bottom pipe
                        shortCircuitedCount++;
                        addOutcome(Outcome.NOT_PERMITTED);
                        double bottomCenterY = pipeBottomY + pipeHeight / 2.0; // exact center of bottom pipe
                        inPipeR2L.add(new Square(cbX, bottomCenterY, SHORT_CIRCUIT_COLOR, moved.speed));
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
                // Only permitted balls reach here; simulate remote call outcome and record in CB
                boolean failed = random.nextDouble() < failureProbability;
                if (failed) {
                    circuitBreaker.onError(durationMs, TimeUnit.MILLISECONDS, new RuntimeException("simulated-failure"));
                    rightSquares.add(new Square(0, 0, Color.web("#ef4444"), 0));
                    addOutcome(Outcome.FAILURE);
                } else {
                    circuitBreaker.onSuccess(durationMs, TimeUnit.MILLISECONDS);
                    rightSquares.add(new Square(0, 0, b.color, 0));
                    addOutcome(Outcome.SUCCESS);
                }
            }
        }

        // Move short-circuited squares along the top pipe to the right
        List<Square> arrivedShort = new ArrayList<>();
        for (int i = 0; i < inPipeL2RShort.size(); i++) {
            Square s = inPipeL2RShort.get(i);
            double newX = s.x + s.speed * deltaSec;
            double newY = clampToPipe(s.y + wobble(deltaSec), pipeTopY, pipeHeight, SQUARE_SIZE / 2.0);
            Square moved = new Square(newX, newY, s.color, s.speed);
            inPipeL2RShort.set(i, moved);
            if (newX >= rightBoxX + BOX_WIDTH / 2.0 - BALL_RADIUS) {
                arrivedShort.add(moved);
            }
        }
        if (!arrivedShort.isEmpty()) {
            inPipeL2RShort.removeAll(arrivedShort);
            // deposit orange squares into right box
            for (Square s : arrivedShort) {
                rightSquares.add(new Square(0, 0, SHORT_CIRCUIT_COLOR, 0));
            }
        }

        // Spawn one square from right box into the BOTTOM pipe at intervals (right -> left)
        if (now - lastSpawnR2LNs >= spawnIntervalR2LNs && !rightSquares.isEmpty()) {
            Square nextSq = rightSquares.pollFirst();
            if (nextSq != null) {
                double pipeEntryX = rightBoxX - (SQUARE_SIZE / 2.0) - 6; // start more to the right inside the pipe
                double pipeEntryY = pipeBottomY + pipeHeight / 2.0; // exact center of bottom pipe
                inPipeR2L.add(new Square(pipeEntryX, pipeEntryY, nextSq.color, 80 + random.nextDouble() * 120));
            }
            lastSpawnR2LNs = now;
        }

        // Move squares in the bottom pipe to the left; on arrival put into left box (as squares)
        List<Square> arrivedBottom = new ArrayList<>();
        for (int i = 0; i < inPipeR2L.size(); i++) {
            Square s = inPipeR2L.get(i);
            double newX = s.x - s.speed * deltaSec; // moving leftwards
            double newY = clampToPipe(s.y + wobble(deltaSec), pipeBottomY, pipeHeight, SQUARE_SIZE / 2.0);
            Square moved = new Square(newX, newY, s.color, s.speed);
            inPipeR2L.set(i, moved);

            // Stop a bit earlier before entering the left box: at the start of the bottom pipe plus small margin
            double pipeBottomX = leftBoxX + BOX_WIDTH;
            if (newX <= pipeBottomX + (SQUARE_SIZE / 2.0) + 6) {
                arrivedBottom.add(moved);
            }
        }
        if (!arrivedBottom.isEmpty()) {
            inPipeR2L.removeAll(arrivedBottom);
            leftSquares.addAll(arrivedBottom.stream().map(s -> new Square(0, 0, s.color, 0)).toList());
        }

        // Gentle randomization of spawn intervals to make flow less uniform
        if (random.nextDouble() < 0.01) {
            spawnIntervalL2RNs = (long) (300L + random.nextDouble() * 600L); // 300–900 ms
        }
        if (random.nextDouble() < 0.01) {
            spawnIntervalR2LNs = (long) (300L + random.nextDouble() * 600L); // 300–900 ms
        }

        // Slowly vary failure probability over time (simulate bad periods)
        if (now - lastProbUpdateMs > 200L) { // update ~5 times/sec
            double t = (now / 1000.0);
            // base 0.2, oscillate +/-0.25 with a slow sine wave
            failureProbability = clamp(0.0, 1.0, 0.2 + 0.25 * Math.sin(t * 0.5) + 0.05 * Math.sin(t * 2.7));
            lastProbUpdateMs = now;
        }
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

        // Draw left box
        g.setStroke(Color.web("#7aa2f7"));
        g.setLineWidth(2.0);
        g.strokeRoundRect(leftBoxX, leftBoxY, BOX_WIDTH, BOX_HEIGHT, 10, 10);
        g.setFill(Color.web("#111827"));
        g.fillRoundRect(leftBoxX, leftBoxY, BOX_WIDTH, BOX_HEIGHT, 10, 10);

        // Draw right box
        g.setStroke(Color.web("#a6e3a1"));
        g.strokeRoundRect(rightBoxX, rightBoxY, BOX_WIDTH, BOX_HEIGHT, 10, 10);
        g.setFill(Color.web("#111827"));
        g.fillRoundRect(rightBoxX, rightBoxY, BOX_WIDTH, BOX_HEIGHT, 10, 10);

        // Draw top pipe
        g.setFill(Color.web("#94a3b8"));
        g.fillRoundRect(pipeTopX, pipeTopY, pipeLength, pipeHeight, 20, 20);
        g.setStroke(Color.web("#475569"));
        g.strokeRoundRect(pipeTopX, pipeTopY, pipeLength, pipeHeight, 20, 20);

        // Draw bottom pipe
        g.setFill(Color.web("#94a3b8"));
        g.fillRoundRect(pipeBottomX, pipeBottomY, pipeLength, pipeHeight, 20, 20);
        g.setStroke(Color.web("#475569"));
        g.strokeRoundRect(pipeBottomX, pipeBottomY, pipeLength, pipeHeight, 20, 20);

        // Draw contents in boxes
        drawMixedInBox(g, leftBoxX, leftBoxY, leftBalls, leftSquares);
        drawSquaresInBox(g, rightBoxX, rightBoxY, rightSquares);

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

        // HUD overlay with CircuitBreaker state and failure rate
        drawHud(g);
        drawBufferPanel(g);
    }

    private void drawCircuitBreakerIcon(GraphicsContext g, double pipeTopX, double pipeTopY, double pipeBottomY, double pipeLength, double pipeHeight) {
        double cbX = pipeTopX + CB_POS_FRACTION * pipeLength;
        double topY = pipeTopY - 8; // slight overlap above top pipe
        double bottomY = pipeBottomY + pipeHeight + 8; // slight overlap below bottom pipe
        double columnW = 36;
        double columnH = bottomY - topY;

        Color stateColor;
        switch (circuitBreaker.getState()) {
            case OPEN -> stateColor = Color.web("#ef4444");
            case HALF_OPEN -> stateColor = Color.web("#f59e0b");
            default -> stateColor = Color.web("#22c55e"); // CLOSED and others
        }

        // Shadow
        g.setFill(Color.color(0,0,0,0.35));
        g.fillRoundRect(cbX - columnW/2 - 3, topY - 3, columnW + 6, columnH + 6, 10, 10);

        // Column background
        g.setFill(Color.color(0.1,0.1,0.1,0.9));
        g.fillRoundRect(cbX - columnW/2, topY, columnW, columnH, 8, 8);

        // State-colored border
        g.setStroke(stateColor);
        g.setLineWidth(2);
        g.strokeRoundRect(cbX - columnW/2, topY, columnW, columnH, 8, 8);

        // Label in the middle of the column
        g.setFill(stateColor);
        double midY = topY + columnH / 2.0;
        g.fillText("CB", cbX - 8, midY + 4);
    }

    private void drawBufferPanel(GraphicsContext g) {
        // Panel near HUD (top-left)
        double x = 260, y = 14;
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("Buffer (latest " + bufferVisualSize + ")", x, y);
        double cell = 10;
        double pad = 2;
        double startY = y + 6;
        int idx = 0;
        for (Outcome o : recentOutcomes) {
            double cx = x + (idx % bufferVisualSize) * (cell + pad);
            double cy = startY + 10;
            Color c = switch (o) {
                case SUCCESS -> Color.web("#22c55e");
                case FAILURE -> Color.web("#ef4444");
                case NOT_PERMITTED -> SHORT_CIRCUIT_COLOR;
            };
            g.setFill(c);
            g.fillRect(cx, cy, cell, cell);
            g.setStroke(Color.color(0,0,0,0.4));
            g.strokeRect(cx, cy, cell, cell);
            idx++;
        }
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

    private void drawSquaresInBox(GraphicsContext g, double boxX, double boxY, Deque<Square> squares) {
        int cols = (int) Math.max(1, Math.floor((BOX_WIDTH - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));
        int rows = (int) Math.max(1, Math.floor((BOX_HEIGHT - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));
        int index = 0;
        for (Square s : squares) {
            int row = index / cols;
            int col = index % cols;
            if (row >= rows) break;
            double x = boxX + BALL_RADIUS + 6 + col * (BALL_RADIUS * 2 + 4);
            double y = boxY + BALL_RADIUS + 6 + row * (BALL_RADIUS * 2 + 4);
            drawSquare(g, x, y, s.color);
            index++;
        }
    }

    private void drawBall(GraphicsContext g, double x, double y, Color color) {
        // Draw hollow ball (outline only)
        g.setStroke(color);
        g.setLineWidth(2.5);
        g.strokeOval(x - BALL_RADIUS, y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
    }

    private void drawSquare(GraphicsContext g, double x, double y, Color color) {
        g.setFill(color);
        g.fillRect(x - SQUARE_SIZE / 2.0, y - SQUARE_SIZE / 2.0, SQUARE_SIZE, SQUARE_SIZE);
        g.setStroke(Color.color(0,0,0,0.4));
        g.strokeRect(x - SQUARE_SIZE / 2.0, y - SQUARE_SIZE / 2.0, SQUARE_SIZE, SQUARE_SIZE);
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
        int inFlight = (int) inPipeL2R.stream().filter(b -> b.cbChecked).count();
        g.fillText(String.format("In-flight (pool): %d / %d", inFlight, MAX_IN_FLIGHT), x, y + 48);
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
