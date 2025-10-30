package io.perfana.visualisation.view;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

public class ServicesAndPipesRenderer {

    public void drawBoxesAndPipes(GraphicsContext g,
                                  double leftBoxX, double leftBoxY,
                                  double rightBoxX, double rightBoxY,
                                  double pipeTopX, double pipeTopY,
                                  double pipeBottomY, double pipeLength, double pipeHeight,
                                  double boxWidth, double boxHeight) {
        // Left service box
        g.setStroke(Color.web("#7aa2f7"));
        g.setLineWidth(2.0);
        g.strokeRoundRect(leftBoxX, leftBoxY, boxWidth, boxHeight, 10, 10);
        g.setFill(Color.web("#111827"));
        g.fillRoundRect(leftBoxX, leftBoxY, boxWidth, boxHeight, 10, 10);

        // Right service box
        g.setStroke(Color.web("#a6e3a1"));
        g.strokeRoundRect(rightBoxX, rightBoxY, boxWidth, boxHeight, 10, 10);
        g.setFill(Color.web("#111827"));
        g.fillRoundRect(rightBoxX, rightBoxY, boxWidth, boxHeight, 10, 10);

        // Top pipe (left -> right)
        g.setFill(Color.web("#94a3b8"));
        g.fillRoundRect(pipeTopX, pipeTopY, pipeLength, pipeHeight, 20, 20);
        g.setStroke(Color.web("#475569"));
        g.strokeRoundRect(pipeTopX, pipeTopY, pipeLength, pipeHeight, 20, 20);

        // Bottom pipe (right -> left)
        double pipeBottomX = pipeTopX;
        g.setFill(Color.web("#94a3b8"));
        g.fillRoundRect(pipeBottomX, pipeBottomY, pipeLength, pipeHeight, 20, 20);
        g.setStroke(Color.web("#475569"));
        g.strokeRoundRect(pipeBottomX, pipeBottomY, pipeLength, pipeHeight, 20, 20);
    }
}
