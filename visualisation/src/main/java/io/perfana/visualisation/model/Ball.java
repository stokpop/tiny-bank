package io.perfana.visualisation.model;

import javafx.scene.paint.Color;

public record Ball(double x, double y, Color color, double speed, long startMs, boolean cbChecked, boolean shortCircuited) {
}