package kz.kaznaru.fim.desktop.component;

import kz.kaznaru.fim.database.FileEventRecord;
import kz.kaznaru.fim.model.FileStatus;
import javafx.scene.control.TableCell;

/** Reusable colored status badge for recent event rows. */
public final class StatusBadgeCell extends TableCell<FileEventRecord, FileStatus> {

    @Override
    protected void updateItem(FileStatus status, boolean empty) {
        super.updateItem(status, empty);
        getStyleClass().removeAll(
                "status-badge", "status-safe", "status-modified",
                "status-new", "status-deleted", "status-error");
        if (empty || status == null) {
            setText(null);
            setGraphic(null);
            return;
        }

        setText(status.name());
        getStyleClass().add("status-badge");
        getStyleClass().add("status-" + status.name().toLowerCase(java.util.Locale.ROOT));
    }
}
