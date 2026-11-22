package kz.kaznaru.fim.desktop.navigation;

import kz.kaznaru.fim.desktop.component.HoverEffects;
import javafx.scene.control.Button;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Applies navigation state to the shell's JavaFX buttons. */
public final class NavigationController {

    private final NavigationState state;
    private final Map<NavigationDestination, Button> buttons;
    private final Consumer<NavigationDestination> navigationHandler;

    public NavigationController(
            NavigationState state,
            Map<NavigationDestination, Button> buttons) {
        this(state, buttons, destination -> { });
    }

    public NavigationController(
            NavigationState state,
            Map<NavigationDestination, Button> buttons,
            Consumer<NavigationDestination> navigationHandler) {
        this.state = Objects.requireNonNull(state, "state");
        this.navigationHandler = Objects.requireNonNull(
                navigationHandler, "navigationHandler");
        Objects.requireNonNull(buttons, "buttons");
        EnumMap<NavigationDestination, Button> copiedButtons =
                new EnumMap<>(NavigationDestination.class);
        for (NavigationDestination destination : NavigationDestination.values()) {
            copiedButtons.put(
                    destination,
                    Objects.requireNonNull(buttons.get(destination), destination.label()));
        }
        this.buttons = Map.copyOf(copiedButtons);
    }

    public void initialize() {
        buttons.forEach((destination, button) -> {
            button.setDisable(!destination.isAvailable());
            button.setAccessibleText(destination.isAvailable()
                    ? destination.label()
                    : destination.label() + ", coming soon");
            if (destination.isAvailable()) {
                button.setOnAction(event -> activate(destination));
            }
            HoverEffects.install(button);
        });
        updateActiveButton();
    }

    public void activate(NavigationDestination destination) {
        Objects.requireNonNull(destination, "destination");
        navigationHandler.accept(destination);
        state.activate(destination);
        updateActiveButton();
    }

    public NavigationState state() {
        return state;
    }

    private void updateActiveButton() {
        buttons.forEach((destination, button) -> {
            button.getStyleClass().remove("active");
            if (state.isActive(destination)) {
                button.getStyleClass().add("active");
            }
        });
    }
}
