package kz.kaznaru.fim.desktop.controller;

import kz.kaznaru.fim.desktop.bootstrap.DesktopServiceContext;
import kz.kaznaru.fim.desktop.navigation.DisposableScreenController;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;

import java.util.Objects;

/** Navigation scaffold for the upcoming file scanner screen. */
public final class FileScannerController implements DisposableScreenController {

    private final DesktopServiceContext serviceContext;

    @FXML
    private Label monitoredRootValue;

    public FileScannerController(DesktopServiceContext serviceContext) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
    }

    @FXML
    private void initialize() {
        String root = serviceContext.configuration().monitoredDirectory()
                .toAbsolutePath().normalize().toString();
        monitoredRootValue.setText(root);
        monitoredRootValue.setTooltip(new Tooltip(root));
    }

    @Override
    public void dispose() {
    }
}
