package kz.kaznaru.fim.desktop.controller;

import javafx.fxml.FXML;
import javafx.scene.control.Label;

public final class DesktopStartupController {

    @FXML
    private Label initializationStatus;

    @FXML
    private void initialize() {
        initializationStatus.setText("Desktop application initialized successfully.");
    }
}
