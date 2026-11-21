package kz.kaznaru.fim.desktop.component;

import javafx.animation.ScaleTransition;
import javafx.scene.Node;
import javafx.util.Duration;

import java.util.Objects;

/** Adds restrained animated emphasis to reusable interactive controls. */
public final class HoverEffects {

    private static final Duration TRANSITION_DURATION = Duration.millis(140);
    private static final double EMPHASIZED_SCALE = 1.015;

    private HoverEffects() {
    }

    public static void install(Node node) {
        Objects.requireNonNull(node, "node");
        ScaleTransition transition = new ScaleTransition(TRANSITION_DURATION, node);
        boolean[] pointerOver = {false};
        node.setOnMouseEntered(event -> {
            pointerOver[0] = true;
            animate(transition, true);
        });
        node.setOnMouseExited(event -> {
            pointerOver[0] = false;
            animate(transition, node.isFocused());
        });
        node.focusedProperty().addListener((observable, wasFocused, isFocused) ->
                animate(transition, isFocused || pointerOver[0]));
    }

    private static void animate(ScaleTransition transition, boolean emphasized) {
        transition.stop();
        double scale = emphasized ? EMPHASIZED_SCALE : 1.0;
        transition.setToX(scale);
        transition.setToY(scale);
        transition.playFromStart();
    }
}
