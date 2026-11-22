package kz.kaznaru.fim.desktop.controller;

import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.navigation.DisposableScreenController;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Objects;

/** Navigation scaffold for the upcoming baseline management screen. */
public final class BaselineManagementController implements DisposableScreenController {

    private final DesktopServiceContext serviceContext;

    @FXML
    private Label baselinePathValue;

    public BaselineManagementController(DesktopServiceContext serviceContext) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    }

    @FXML
    private void initialize() {
        String baselinePath = serviceContext.configuration().baselineFile()
                .toAbsolutePath().normalize().toString();
        baselinePathValue.setText(baselinePath);
        baselinePathValue.setTooltip(new Tooltip(baselinePath));
    }

    @Override
    public void dispose() {
    }
}
