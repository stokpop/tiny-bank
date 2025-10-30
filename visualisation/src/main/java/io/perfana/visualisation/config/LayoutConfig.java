package io.perfana.visualisation.config;

public class LayoutConfig {
    // Canvas
    public static final double WIDTH = 900;
    public static final double HEIGHT = 440;

    // Boxes (services)
    public static final double BOX_MARGIN = 40;
    public static final double BOX_WIDTH = 220;
    public static final double BOX_HEIGHT = 300;

    // Pipes
    public static final double PIPE_WIDTH = 80;
    public static final double PIPE_HEIGHT = 40;

    // Shapes
    public static final double BALL_RADIUS = 8;
    public static final double SQUARE_SIZE = BALL_RADIUS * 2;

    // Circuit Breaker position (fraction along the pipe from left)
    public static final double CB_POS_FRACTION = 0.35; // 35%

    // Derived geometry helpers
    public double leftBoxX() { return BOX_MARGIN; }
    public double leftBoxY() { return (HEIGHT - BOX_HEIGHT) / 2.0; }

    public double rightBoxX() { return WIDTH - BOX_MARGIN - BOX_WIDTH; }
    public double rightBoxY() { return leftBoxY(); }

    public double pipeTopX() { return leftBoxX() + BOX_WIDTH; }
    public double pipeTopY() { return HEIGHT / 2.0 - PIPE_HEIGHT; }
    public double pipeBottomY() { return HEIGHT / 2.0 + PIPE_HEIGHT; }
    public double pipeLength() { return rightBoxX() - pipeTopX(); }

    public double cbX() { return pipeTopX() + CB_POS_FRACTION * pipeLength(); }
}