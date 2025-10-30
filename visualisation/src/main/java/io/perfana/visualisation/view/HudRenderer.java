package io.perfana.visualisation.view;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.perfana.visualisation.config.LayoutConfig;
import io.perfana.visualisation.model.Outcome;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.Deque;

public class HudRenderer {

    public void draw(GraphicsContext g,
                     CircuitBreaker circuitBreaker,
                     int currentInFlight,
                     int maxInFlight,
                     double failureProbability,
                     Deque<Outcome> recentOutcomes,
                     int bufferVisualSize) {
        drawHud(g, circuitBreaker, currentInFlight, maxInFlight, failureProbability);
        drawBufferPanel(g, recentOutcomes, bufferVisualSize);
    }

    private void drawHud(GraphicsContext g,
                         CircuitBreaker circuitBreaker,
                         int currentInFlight,
                         int maxInFlight,
                         double failureProbability) {
        double x = 20, y = 18;
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("CircuitBreaker: " + circuitBreaker.getState(), x, y);

        var metrics = circuitBreaker.getMetrics();
        float failureRate = metrics.getFailureRate();
        float buffered = metrics.getNumberOfBufferedCalls();
        float notPermitted = metrics.getNumberOfNotPermittedCalls();
        float slowCallRate = metrics.getSlowCallRate();

        float failureRateThreshold = 50.0f; // mirrors default in BallFlowApp

        g.fillText(String.format("Failure rate: %.1f%% (threshold %.0f%%)", failureRate, failureRateThreshold), x, y + 16);
        g.fillText(String.format("Buffered calls: %.0f  Not permitted: %.0f  Slow rate: %.1f%%", buffered, notPermitted, slowCallRate), x, y + 32);
        g.fillText(String.format("In-flight (pool): %d / %d", currentInFlight, maxInFlight), x, y + 48);
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

    private void drawBufferPanel(GraphicsContext g, Deque<Outcome> recentOutcomes, int bufferVisualSize) {
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
                case NOT_PERMITTED -> Color.web("#f59e0b");
            };
            g.setFill(c);
            g.fillRect(cx, cy, cell, cell);
            g.setStroke(Color.color(0,0,0,0.4));
            g.strokeRect(cx, cy, cell, cell);
            idx++;
        }
    }

    private static double clamp(double min, double max, double v) {
        return Math.max(min, Math.min(max, v));
    }
}
