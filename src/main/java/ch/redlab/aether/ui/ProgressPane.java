package ch.redlab.aether.ui;

import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToDoubleFunction;

/**
 * Progress tab (F-08): all transfers of this session, sent and received, with progress, amount and speed.
 * <p>
 * Must only be used on the JavaFX thread.
 */
public class ProgressPane extends ScrollPane {

    private final VBox rows = new VBox(12);
    private final Label emptyLabel = new Label("No transfers yet.");
    private final Button clearButton = new Button("Clear finished");
    private final Map<UUID, Row> byId = new LinkedHashMap<>();

    private Consumer<UUID> onCancel = id -> { };
    private ToDoubleFunction<UUID> speedOf = id -> 0;

    public ProgressPane() {
        getStyleClass().add("requests-pane");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        emptyLabel.getStyleClass().add("hint-label");
        clearButton.getStyleClass().add("aero-button");
        clearButton.setOnAction(e -> clearFinished());
        HBox toolbar = new HBox(clearButton);
        toolbar.setAlignment(Pos.CENTER_RIGHT);

        rows.getStyleClass().add("request-list");
        rows.setAlignment(Pos.TOP_CENTER);
        rows.setMaxWidth(560);
        rows.getChildren().addAll(toolbar, emptyLabel);
        setContent(new StackPane(rows));
        updateClearButton();
    }

    /** Called when the user cancels a transfer. */
    public void setOnCancel(Consumer<UUID> onCancel) {
        this.onCancel = onCancel;
    }

    /** Where the current speed of a transfer comes from (bytes per second). */
    public void setSpeedSource(ToDoubleFunction<UUID> speedOf) {
        this.speedOf = speedOf;
    }

    /** Adds the transfer if it is new, otherwise refreshes its row. */
    public void update(Transfer transfer) {
        Row row = byId.get(transfer.getId());
        if (row == null) {
            row = new Row(transfer);
            byId.put(transfer.getId(), row);
            rows.getChildren().remove(emptyLabel);
            rows.getChildren().add(1, row);
        }
        row.refresh();
        updateClearButton();
    }

    /** Refreshes the speed of running transfers; called regularly by a timer. */
    public void refreshSpeeds() {
        byId.values().stream()
                .filter(r -> r.transfer.getStatus() == TransferStatus.RUNNING)
                .forEach(Row::refresh);
    }

    /** Number of transfers that are not finished yet. */
    public int activeCount() {
        return (int) byId.values().stream().filter(r -> !r.transfer.getStatus().isFinal()).count();
    }

    private void clearFinished() {
        byId.values().removeIf(row -> {
            boolean finished = row.transfer.getStatus().isFinal();
            if (finished) {
                rows.getChildren().remove(row);
            }
            return finished;
        });
        if (byId.isEmpty()) {
            rows.getChildren().add(emptyLabel);
        }
        updateClearButton();
    }

    private void updateClearButton() {
        clearButton.setDisable(byId.values().stream().noneMatch(r -> r.transfer.getStatus().isFinal()));
    }

    /** One transfer. */
    private final class Row extends VBox {

        private final Transfer transfer;
        private final Label detail = new Label();
        private final ProgressBar progress = new ProgressBar(0);
        private final Button cancel = new Button("Cancel");

        Row(Transfer transfer) {
            super(8);
            this.transfer = transfer;
            getStyleClass().add("request-card");

            Label title = new Label(TransferText.title(transfer));
            title.getStyleClass().add("request-title");
            Label file = new Label(transfer.getFile().name());
            file.getStyleClass().add("info-value");

            detail.getStyleClass().add("transfer-label");
            detail.setWrapText(true);
            progress.getStyleClass().add("aero-progress");
            progress.setMaxWidth(Double.MAX_VALUE);
            cancel.getStyleClass().add("aero-button");
            cancel.setOnAction(e -> {
                cancel.setDisable(true);
                onCancel.accept(transfer.getId());
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox bottom = new HBox(10, detail, spacer, cancel);
            bottom.setAlignment(Pos.CENTER_LEFT);
            getChildren().addAll(title, file, progress, bottom);
        }

        void refresh() {
            TransferStatus s = transfer.getStatus();
            detail.setText(TransferText.detail(transfer, speedOf.applyAsDouble(transfer.getId())));
            progress.setProgress(transfer.getProgress());
            shown(progress, s == TransferStatus.RUNNING || s == TransferStatus.COMPLETED);
            shown(cancel, !s.isFinal());

            getStyleClass().removeAll("request-done", "request-failed");
            if (s == TransferStatus.COMPLETED) {
                getStyleClass().add("request-done");
            } else if (s.isFinal()) {
                getStyleClass().add("request-failed");
            }
        }
    }

    private static void shown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
