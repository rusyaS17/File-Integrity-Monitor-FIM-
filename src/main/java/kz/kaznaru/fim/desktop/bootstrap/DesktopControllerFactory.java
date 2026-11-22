package kz.kaznaru.fim.desktop.bootstrap;

import kz.kaznaru.fim.desktop.controller.BaselineManagementController;
import kz.kaznaru.fim.desktop.controller.DashboardController;
import kz.kaznaru.fim.desktop.controller.FileScannerController;

import java.util.Objects;

/** Creates FXML screen controllers with the shared desktop service context. */
public final class DesktopControllerFactory {

    private final DesktopServiceContext serviceContext;
    private final Runnable monitoringStateChanged;

    public DesktopControllerFactory(
            DesktopServiceContext serviceContext,
            Runnable monitoringStateChanged) {
        this.serviceContext = Objects.requireNonNull(serviceContext, "serviceContext");
        this.monitoringStateChanged = Objects.requireNonNull(
                monitoringStateChanged, "monitoringStateChanged");
    }

    public Object create(Class<?> controllerType) {
        Objects.requireNonNull(controllerType, "controllerType");
        if (controllerType == DashboardController.class) {
            return new DashboardController(serviceContext, monitoringStateChanged);
        }
        if (controllerType == FileScannerController.class) {
            return new FileScannerController(serviceContext);
        }
        if (controllerType == BaselineManagementController.class) {
            return new BaselineManagementController(serviceContext);
        }
        throw new IllegalArgumentException(
                "Unsupported desktop screen controller: " + controllerType.getName());
    }
}
