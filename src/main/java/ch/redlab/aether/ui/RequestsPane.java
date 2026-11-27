package ch.redlab.aether.ui;

import ch.redlab.aether.core.IncomingRequest;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Requests tab (F-04 / F-05, UC3): one card per incoming transfer with sender, file, size and security code.
 * The user accepts or declines; afterwards the card shows the progress and the result (F-08 / F-09).
 * <p>
 * Must only be used on the JavaFX thread.
 */
public class RequestsPane extends ScrollPane {

    private final VBox cards = new VBox(12);
    private final Label emptyLabel = new Label("No incoming requests.");
    private final Map<UUID, RequestCard> byId = new LinkedHashMap<>();
    private final ReadOnlyIntegerWrapper pending = new ReadOnlyIntegerWrapper(0);

    private Consumer<UUID> onCancel = id -> { };
    private Consumer<Path> onOpenFolder = folder -> { };

    public RequestsPane() {
        getStyleClass().add("requests-pane");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);

        emptyLabel.getStyleClass().add("hint-label");
        cards.getStyleClass().add("request-list");
        cards.setAlignment(Pos.TOP_CENTER);
        cards.getChildren().add(emptyLabel);

        // Keeps the list centred and readable on wide windows.
        StackPane centre = new StackPane(cards);
        cards.setMaxWidth(560);
        setContent(centre);
    }

    /** Called when the user cancels a running transfer. */
    public void setOnCancel(Consumer<UUID> onCancel) {
        this.onCancel = onCancel;
    }

    /** Called with the folder of a received file when the user clicks "Open folder". */
    public void setOnOpenFolder(Consumer<Path> onOpenFolder) {
        this.onOpenFolder = onOpenFolder;
    }

    /** Number of requests still waiting for a decision. */
    public ReadOnlyIntegerProperty pendingProperty() {
        return pending.getReadOnlyProperty();
    }

    /** Shows a new request at the top of the list. */
    public void addRequest(IncomingRequest request) {
        Transfer transfer = request.getTransfer();
        if (byId.containsKey(transfer.getId())) {
            return;
        }
        RequestCard card = new RequestCard(request);
        byId.put(transfer.getId(), card);
        cards.getChildren().remove(emptyLabel);
        cards.getChildren().addFirst(card);
        updatePending();
    }

    /** Updates the card of an incoming transfer; unknown transfers are ignored. */
    public void update(Transfer transfer) {
        RequestCard card = byId.get(transfer.getId());
        if (card != null) {
            card.refresh();
            updatePending();
        }
    }

    private void remove(RequestCard card) {
        byId.values().remove(card);
        cards.getChildren().remove(card);
        if (byId.isEmpty()) {
            cards.getChildren().add(emptyLabel);
        }
        updatePending();
    }

    private void updatePending() {
        pending.set((int) byId.values().stream().filter(RequestCard::isWaiting).count());
    }

    /** One incoming transfer. */
    private final class RequestCard extends VBox {

        private final IncomingRequest request;
        private final Transfer transfer;
        private final Label status = new Label();
        private final ProgressBar progress = new ProgressBar(0);
        private final Button decline = new Button("Decline");
        private final Button accept = new Button("Accept");
        private final Button cancel = new Button("Cancel");
        private final Button openFolder = new Button("Open folder");
        private final Button dismiss = new Button("Remove");
        /** Set when the user clicked; the status only changes once the backend sent the answer. */
        private boolean decided;

        RequestCard(IncomingRequest request) {
            super(10);
            this.request = request;
            this.transfer = request.getTransfer();
            getStyleClass().add("request-card");

            Label title = new Label("Incoming file from " + transfer.getPeerName());
            title.getStyleClass().add("request-title");

            GridPane info = new GridPane();
            info.setHgap(10);
            info.setVgap(4);
            info.getColumnConstraints().addAll(new ColumnConstraints(110), new ColumnConstraints());
            addRow(info, 0, "File", value(transfer.getFile().name()));
            addRow(info, 1, "Size", value(MainController.formatSize(transfer.getFile().size())));
            Label code = new Label(transfer.getSecurityCode() == null ? "-" : IncomingText.spacedCode(transfer.getSecurityCode()));
            code.getStyleClass().add("security-code");
            addRow(info, 2, "Security code", code);

            status.getStyleClass().add("transfer-label");
            status.setWrapText(true);
            progress.getStyleClass().add("aero-progress");
            progress.setMaxWidth(Double.MAX_VALUE);

            for (Button b : new Button[] {decline, accept, cancel, openFolder, dismiss}) {
                b.getStyleClass().add("aero-button");
            }
            accept.getStyleClass().add("accept-button");
            decline.setOnAction(e -> decide(false));
            accept.setOnAction(e -> decide(true));
            cancel.setOnAction(e -> {
                cancel.setDisable(true);
                onCancel.accept(transfer.getId());
            });
            openFolder.setOnAction(e -> {
                Path saved = transfer.getSavedFile();
                if (saved != null && saved.getParent() != null) {
                    onOpenFolder.accept(saved.getParent());
                }
            });
            dismiss.setOnAction(e -> remove(this));

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox buttons = new HBox(10, spacer, decline, accept, cancel, openFolder, dismiss);
            buttons.setAlignment(Pos.CENTER_RIGHT);

            getChildren().addAll(title, info, status, progress, buttons);
            refresh();
        }

        boolean isWaiting() {
            return transfer.getStatus() == TransferStatus.REQUESTED && !decided;
        }

        private void decide(boolean yes) {
            decided = true;
            accept.setDisable(true);
            decline.setDisable(true);
            if (yes) {
                request.accept();
            } else {
                request.reject();
            }
            updatePending();
        }

        /** Shows the controls that fit the current status. */
        void refresh() {
            TransferStatus s = transfer.getStatus();
            boolean finished = s.isFinal();
            status.setText(IncomingText.status(transfer));
            progress.setProgress(transfer.getProgress());

            shown(decline, s == TransferStatus.REQUESTED);
            shown(accept, s == TransferStatus.REQUESTED);
            shown(progress, s == TransferStatus.RUNNING || s == TransferStatus.COMPLETED);
            shown(cancel, s == TransferStatus.ACCEPTED || s == TransferStatus.RUNNING);
            shown(openFolder, s == TransferStatus.COMPLETED && transfer.getSavedFile() != null);
            shown(dismiss, finished);

            getStyleClass().removeAll("request-done", "request-failed");
            if (s == TransferStatus.COMPLETED) {
                getStyleClass().add("request-done");
            } else if (finished) {
                getStyleClass().add("request-failed");
            }
        }

        private Label value(String text) {
            Label label = new Label(text);
            label.getStyleClass().add("info-value");
            return label;
        }

        private void addRow(GridPane grid, int row, String key, Label value) {
            Label label = new Label(key);
            label.getStyleClass().add("info-key");
            grid.add(label, 0, row);
            grid.add(value, 1, row);
        }
    }

    private static void shown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
