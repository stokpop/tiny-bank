package io.perfana.visualisation.view;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.perfana.visualisation.config.LayoutConfig;
import io.perfana.visualisation.model.Outcome;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.Deque;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class HudRenderer {

    public void draw(GraphicsContext g,
                     CircuitBreaker circuitBreaker,
                     int currentInFlight,
                     int maxInFlight,
                     double failureProbability,
                     Deque<Outcome> recentOutcomes,
                     int bufferVisualSize,
                     Double openCountdownSec,
                     Deque<String> cbEvents,
                     long simElapsedMs) {
        drawHud(g, circuitBreaker, currentInFlight, maxInFlight, failureProbability, recentOutcomes, openCountdownSec);
        drawBufferPanel(g, recentOutcomes, bufferVisualSize);
        drawEventPanel(g, cbEvents);
        drawClock(g, simElapsedMs);
    }

    private void drawHud(GraphicsContext g,
                         CircuitBreaker circuitBreaker,
                         int currentInFlight,
                         int maxInFlight,
                         double failureProbability,
                         Deque<Outcome> recentOutcomes,
                         Double openCountdownSec) {
        double x = 20, y = 18;
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("CircuitBreaker: " + circuitBreaker.getState(), x, y);

        var metrics = circuitBreaker.getMetrics();
        float buffered = metrics.getNumberOfBufferedCalls();
        float notPermitted = metrics.getNumberOfNotPermittedCalls();
        float slowCallRate = metrics.getSlowCallRate();

        // Read actual config so visuals match the breaker behaviour precisely
        float failureRateThreshold = circuitBreaker.getCircuitBreakerConfig().getFailureRateThreshold();
        int minimumNumberOfCalls = circuitBreaker.getCircuitBreakerConfig().getMinimumNumberOfCalls();

        // Compute failure rate from the same buffer we visualize so it stays in sync
        int total = recentOutcomes.size();
        int failures = 0;
        for (Outcome o : recentOutcomes) {
            if (o == Outcome.FAILURE) failures++;
        }
        double failureRateBuf = total > 0 ? (failures * 100.0) / total : 0.0;

        g.fillText(String.format("Failure rate (buffer): %.1f%% (threshold %.0f%%)", failureRateBuf, failureRateThreshold), x, y + 16);
        g.fillText(String.format("Buffered calls: %.0f  Not permitted: %.0f  Slow rate: %.1f%%", buffered, notPermitted, slowCallRate), x, y + 32);
        g.fillText(String.format("In-flight (pool): %d / %d", currentInFlight, maxInFlight), x, y + 48);
        g.fillText(String.format("Failure probability (sim): %.0f%%", failureProbability * 100.0), x, y + 64);
        if (circuitBreaker.getState() == CircuitBreaker.State.OPEN && openCountdownSec != null) {
            g.fillText(String.format("Open wait remaining: %.1fs", Math.max(0.0, openCountdownSec)), x, y + 80);
        }

        // Bar showing failure rate vs threshold
        double barX = x;
        double barY = y + 92;
        double barW = 220;
        double barH = 10;
        g.setFill(Color.color(1,1,1,0.15));
        g.fillRect(barX, barY, barW, barH);
        double frac = clamp(0, 1, failureRateBuf / 100.0);
        // Only paint the bar red if we have at least the configured minimum number of calls
        // and the failure rate meets/exceeds the threshold; otherwise keep it green to avoid
        // suggesting the breaker should open too early.
        boolean thresholdActive = total >= minimumNumberOfCalls;
        Color barColor = (thresholdActive && failureRateBuf >= failureRateThreshold)
                ? Color.web("#ef4444")
                : Color.web("#22c55e");
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

    private void drawEventPanel(GraphicsContext g, Deque<String> cbEvents) {
        if (cbEvents == null || cbEvents.isEmpty()) return;
        double x = 420, y = 14;
        g.setFill(Color.color(1,1,1,0.9));
        g.fillText("CB events (latest)", x, y);
        int maxLines = 8;
        int i = 0;
        for (String ev : cbEvents) {
            if (i >= maxLines) break;
            String line = formatEventWithTimestamp(ev);
            g.fillText(line, x, y + 14 + (i * 14));
            i++;
        }
    }

    private String formatEventWithTimestamp(String ev) {
        try {
            int idx = ev.indexOf(':');
            if (idx > 0) {
                String millisStr = ev.substring(0, idx).trim();
                long ms = Long.parseLong(millisStr);
                String msg = ev.substring(Math.min(idx + 2, ev.length()));
                LocalTime lt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalTime();
                String mmss = lt.format(DateTimeFormatter.ofPattern("mm:ss"));
                return "[" + mmss + "] " + msg;
            }
        } catch (Exception ignored) {
        }
        return ev; // fallback
    }

    private void drawClock(GraphicsContext g, long simElapsedMs) {
        double padding = 16.0;
        double canvasW = g.getCanvas().getWidth();
        double xRight = canvasW - padding;
        double y = 18.0;
        if (simElapsedMs < 0) simElapsedMs = 0;
        long totalSeconds = simElapsedMs / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        String mmss = String.format("%02d:%02d", minutes, seconds);
        g.setFill(Color.color(1,1,1,0.9));
        // Right-align by subtracting approximate text width using a monospace-like assumption (not exact but sufficient)
        // Alternatively, position slightly to the left of the right padding for safety
        g.fillText(mmss, xRight - 40, y); // 40px offset to keep within view
    }

    private static double clamp(double min, double max, double v) {
        return Math.max(min, Math.min(max, v));
    }
}
