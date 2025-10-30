package io.perfana.visualisation.view;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class CircuitBreakerRenderer {

    public void drawColumn(GraphicsContext g,
                           CircuitBreaker circuitBreaker,
                           double cbX,
                           double topPipeY,
                           double bottomPipeY,
                           double pipeHeight) {
        double topY = topPipeY - 8; // slight overlap above top pipe
        double bottomY = bottomPipeY + pipeHeight + 8; // slight overlap below bottom pipe
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

        // Label
        g.setFill(stateColor);
        double midY = topY + columnH / 2.0;
        g.fillText("CB", cbX - 8, midY + 4);
    }
}
