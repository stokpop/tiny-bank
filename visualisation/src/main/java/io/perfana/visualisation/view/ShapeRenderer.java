package io.perfana.visualisation.view;

import io.perfana.visualisation.config.LayoutConfig;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class ShapeRenderer {

    public void drawBall(GraphicsContext g, double x, double y, Color color) {
        g.setStroke(color);
        g.setLineWidth(2.5);
        double r = LayoutConfig.BALL_RADIUS;
        g.strokeOval(x - r, y - r, r * 2, r * 2);
    }

    public void drawSquare(GraphicsContext g, double x, double y, Color color) {
        double size = LayoutConfig.SQUARE_SIZE;
        g.setFill(color);
        g.fillRect(x - size / 2.0, y - size / 2.0, size, size);
        g.setStroke(Color.color(0,0,0,0.4));
        g.strokeRect(x - size / 2.0, y - size / 2.0, size, size);
    }
}
