package io.perfana.visualisation;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * JavaFX visualisation showing balls moving from the left box through the top pipe to the right box,
 * where they turn into squares. Squares are then sent back through a second (bottom) pipe to the left box.
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

    private final Random random = new Random();

    private record Ball(double x, double y, Color color, double speed) {}
    private record Square(double x, double y, Color color, double speed) {}

    // Left box contents: balls (original) and squares (returned)
    private final Deque<Ball> leftBalls = new ArrayDeque<>();
    private final Deque<Square> leftSquares = new ArrayDeque<>();

    // Right box contents: squares (converted)
    private final Deque<Square> rightSquares = new ArrayDeque<>();

    // Pipes
    private final List<Ball> inPipeL2R = new ArrayList<>(); // top pipe: left -> right
    private final List<Square> inPipeR2L = new ArrayList<>(); // bottom pipe: right -> left

    private long lastSpawnL2RNs = 0;
    private long spawnIntervalL2RNs = 500_000_000L; // 0.5s default

    private long lastSpawnR2LNs = 0;
    private long spawnIntervalR2LNs = 600_000_000L; // 0.6s default

    @Override
    public void start(Stage stage) {
        stage.setTitle("Tiny Bank - Flow Visualisation");

        BorderPane root = new BorderPane();
        Canvas canvas = new Canvas(WIDTH, HEIGHT);
        root.setCenter(canvas);

        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#0f141a"));
        stage.setScene(scene);
        stage.setResizable(false);
        stage.show();

        // Pre-fill left box with some balls
        for (int i = 0; i < 30; i++) {
            leftBalls.add(createRandomBallInLeftBox());
        }

        GraphicsContext g = canvas.getGraphicsContext2D();

        AnimationTimer timer = new AnimationTimer() {
            long lastTime = 0;
            @Override
            public void handle(long now) {
                if (lastTime == 0) {
                    lastTime = now;
                    return;
                }
                double deltaSec = (now - lastTime) / 1_000_000_000.0;
                lastTime = now;

                update(now, deltaSec);
                draw(g);
            }
        };
        timer.start();
    }

    private void update(long now, double deltaSec) {
        // Geometry used for movement decisions
        double leftBoxX = BOX_MARGIN;
        double rightBoxX = WIDTH - BOX_MARGIN - BOX_WIDTH;

        // Spawn one ball from left box into the TOP pipe at intervals (left -> right)
        if (now - lastSpawnL2RNs >= spawnIntervalL2RNs && !leftBalls.isEmpty()) {
            Ball next = leftBalls.pollFirst();
            if (next != null) {
                double pipeEntryX = leftBoxX + BOX_WIDTH + (PIPE_WIDTH / 2.0);
                double pipeEntryY = HEIGHT / 2.0 - 20 + 20; // center of top pipe
                inPipeL2R.add(new Ball(pipeEntryX, pipeEntryY, next.color, 80 + random.nextDouble() * 120));
            }
            lastSpawnL2RNs = now;
        }

        // Move balls in the top pipe to the right; on arrival convert to squares in right box
        List<Ball> arrivedTop = new ArrayList<>();
        for (int i = 0; i < inPipeL2R.size(); i++) {
            Ball b = inPipeL2R.get(i);
            double newX = b.x + b.speed * deltaSec;
            Ball moved = new Ball(newX, b.y + wobble(deltaSec), b.color, b.speed);
            inPipeL2R.set(i, moved);

            if (newX >= rightBoxX + BOX_WIDTH / 2.0 - BALL_RADIUS) {
                arrivedTop.add(moved);
            }
        }
        if (!arrivedTop.isEmpty()) {
            inPipeL2R.removeAll(arrivedTop);
            for (Ball b : arrivedTop) {
                // Convert arriving balls into squares in the right box
                rightSquares.add(new Square(0, 0, b.color, 0));
            }
        }

        // Spawn one square from right box into the BOTTOM pipe at intervals (right -> left)
        if (now - lastSpawnR2LNs >= spawnIntervalR2LNs && !rightSquares.isEmpty()) {
            Square nextSq = rightSquares.pollFirst();
            if (nextSq != null) {
                double pipeEntryX = rightBoxX - (PIPE_WIDTH / 2.0);
                double pipeEntryY = HEIGHT / 2.0 + 60 + 20; // center of bottom pipe
                inPipeR2L.add(new Square(pipeEntryX, pipeEntryY, nextSq.color, 80 + random.nextDouble() * 120));
            }
            lastSpawnR2LNs = now;
        }

        // Move squares in the bottom pipe to the left; on arrival put into left box (as squares)
        List<Square> arrivedBottom = new ArrayList<>();
        for (int i = 0; i < inPipeR2L.size(); i++) {
            Square s = inPipeR2L.get(i);
            double newX = s.x - s.speed * deltaSec; // moving leftwards
            Square moved = new Square(newX, s.y + wobble(deltaSec), s.color, s.speed);
            inPipeR2L.set(i, moved);

            if (newX <= leftBoxX + BOX_WIDTH / 2.0 + BALL_RADIUS) {
                arrivedBottom.add(moved);
            }
        }
        if (!arrivedBottom.isEmpty()) {
            inPipeR2L.removeAll(arrivedBottom);
            leftSquares.addAll(arrivedBottom.stream().map(s -> new Square(0, 0, s.color, 0)).toList());
        }

        // Gentle randomization of spawn intervals to make flow less uniform
        if (random.nextDouble() < 0.01) {
            spawnIntervalL2RNs = (long) (300_000_000L + random.nextDouble() * 600_000_000L);
        }
        if (random.nextDouble() < 0.01) {
            spawnIntervalR2LNs = (long) (300_000_000L + random.nextDouble() * 600_000_000L);
        }
    }

    private double wobble(double deltaSec) {
        return (random.nextDouble() - 0.5) * 10 * deltaSec * 60; // small vertical jitter
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
        for (Square s : inPipeR2L) {
            drawSquare(g, s.x, s.y, s.color);
        }
    }

    private void drawMixedInBox(GraphicsContext g, double boxX, double boxY, Deque<Ball> balls, Deque<Square> squares) {
        int cols = (int) Math.max(1, Math.floor((BOX_WIDTH - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));
        int rows = (int) Math.max(1, Math.floor((BOX_HEIGHT - 2 * BALL_RADIUS) / (BALL_RADIUS * 2 + 4)));
        int index = 0;
        for (Ball b : balls) {
            int row = index / cols;
            int col = index % cols;
            if (row >= rows) break;
            double x = boxX + BALL_RADIUS + 6 + col * (BALL_RADIUS * 2 + 4);
            double y = boxY + BALL_RADIUS + 6 + row * (BALL_RADIUS * 2 + 4);
            drawBall(g, x, y, b.color);
            index++;
        }
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
        g.setFill(color);
        g.fillOval(x - BALL_RADIUS, y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
        g.setStroke(Color.color(0,0,0,0.4));
        g.strokeOval(x - BALL_RADIUS, y - BALL_RADIUS, BALL_RADIUS * 2, BALL_RADIUS * 2);
    }

    private void drawSquare(GraphicsContext g, double x, double y, Color color) {
        g.setFill(color);
        g.fillRect(x - SQUARE_SIZE / 2.0, y - SQUARE_SIZE / 2.0, SQUARE_SIZE, SQUARE_SIZE);
        g.setStroke(Color.color(0,0,0,0.4));
        g.strokeRect(x - SQUARE_SIZE / 2.0, y - SQUARE_SIZE / 2.0, SQUARE_SIZE, SQUARE_SIZE);
    }

    private Ball createRandomBallInLeftBox() {
        double hue = random.nextDouble();
        Color color = Color.hsb(hue * 360, 0.7, 0.95);
        return new Ball(0, 0, color, 0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
