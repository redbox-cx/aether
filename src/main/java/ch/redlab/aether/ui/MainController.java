package ch.redlab.aether.ui;

import ch.redlab.aether.core.AetherBackend;
import ch.redlab.aether.core.IncomingRequest;
import ch.redlab.aether.core.TransferListener;
import ch.redlab.aether.model.Device;
import ch.redlab.aether.model.FileInfo;
import ch.redlab.aether.model.Transfer;
import ch.redlab.aether.model.TransferStatus;
import javafx.geometry.Insets;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Controller of the main window (F-02 choose device, F-03 choose and send file).
 * Contains no network logic, it only talks to {@link AetherBackend}.
 * All backend callbacks arrive on background threads and are moved to the FX thread with
 * {@link Platform#runLater(Runnable)}.
 */
public class MainController {

    private final AetherBackend backend;
    private final ExecutorService hashExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aether-sha256");
        t.setDaemon(true);
        return t;
    });

    private final ObservableList<Device> devices = FXCollections.observableArrayList();
    private final FilteredList<Device> filteredDevices = new FilteredList<>(devices, d -> true);

    @FXML private HBox root;
    @FXML private HBox titleStrip;
    @FXML private BorderPane mainRoot;
    @FXML private StackPane header;
    @FXML private Region headerBackground;
    @FXML private HBox windowButtons;

    @FXML private TextField searchField;
    @FXML private ListView<Device> deviceList;
    @FXML private Label emptyLabel;

    @FXML private ToggleGroup tabs;
    @FXML private ToggleButton uploadTab;
    @FXML private ToggleButton requestsTab;
    @FXML private ToggleButton progressTab;
    @FXML private ToggleButton settingsTab;
    @FXML private StackPane contentArea;
    @FXML private VBox uploadPane;
    @FXML private Label placeholderPane;

    @FXML private Label targetLabel;
    @FXML private Button chooseFileButton;
    @FXML private GridPane fileInfoGrid;
    @FXML private Label fileNameLabel;
    @FXML private Label fileSizeLabel;
    @FXML private ProgressIndicator hashProgress;
    @FXML private TextField hashField;
    @FXML private Button sendButton;
    @FXML private VBox transferBox;
    @FXML private Label transferLabel;
    @FXML private ProgressBar transferProgress;

    /** Picked file; null until the user chose one. */
    private Path selectedFile;
    /** Name, size and SHA-256 of {@link #selectedFile}; null while the hash is still computed. */
    private FileInfo selectedFileInfo;
    private Task<FileInfo> hashTask;
    /** Id of the transfer started last; only its updates are shown in the upload tab. */
    private volatile UUID currentTransferId;

    private Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;

    public MainController(AetherBackend backend) {
        this.backend = backend;
    }

    @FXML
    private void initialize() {
        setUpDeviceList();
        setUpTabs();
        setUpFileDrop();

        backend.addDeviceListener(list -> Platform.runLater(() -> updateDevices(list)));
        backend.addTransferListener(new TransferListener() {
            @Override
            public void onIncomingRequest(IncomingRequest request) {
                // F-04 / F-05 (receive dialog) is not part of this view yet.
            }

            @Override
            public void onTransferUpdated(Transfer transfer) {
                Platform.runLater(() -> showTransfer(transfer));
            }
        });
        updateDevices(backend.getDevices());
        updateSendButton();
    }

    /** Called by {@link AetherApp} once the stage exists. Moving, resizing and the window buttons come from Windows. */
    void attach(Stage stage) {
        this.stage = stage;
        // The native frame brings its own buttons; the Figma buttons are hidden and the grey header
        // starts at the very top instead of leaving the transparent strip of the frameless design.
        setShown(windowButtons, false);
        StackPane.setMargin(headerBackground, new Insets(0));
    }

    /** Places the status bar below the content (Figma 20:9). */
    void setStatusBar(Node statusBar) {
        mainRoot.setBottom(statusBar);
    }

    /** Stops the background hash thread. The backend itself is stopped by {@link AetherApp}. */
    void shutdown() {
        if (hashTask != null) {
            hashTask.cancel();
        }
        hashExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- devices (F-02)

    private void setUpDeviceList() {
        deviceList.setItems(filteredDevices);
        deviceList.setCellFactory(list -> new DeviceCell());

        searchField.textProperty().addListener((obs, old, text) -> {
            String query = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
            filteredDevices.setPredicate(d -> query.isEmpty()
                    || d.name().toLowerCase(Locale.ROOT).contains(query)
                    || d.address().getHostAddress().contains(query));
        });

        emptyLabel.visibleProperty().bind(Bindings.isEmpty(filteredDevices));
        emptyLabel.textProperty().bind(Bindings.when(Bindings.isEmpty(devices))
                .then("Keine Geräte gefunden")
                .otherwise("Kein Gerät passt zur Suche"));

        deviceList.getSelectionModel().selectedItemProperty().addListener((obs, old, device) -> {
            targetLabel.setText(device == null
                    ? "Wähle links ein Gerät. Datei hierher ziehen oder auswählen."
                    : "Empfänger: " + device.name());
            updateSendButton();
        });
    }

    /** Replaces the list and keeps the selected device selected if it is still there. */
    private void updateDevices(List<Device> newDevices) {
        Device selected = deviceList.getSelectionModel().getSelectedItem();
        devices.setAll(newDevices);
        if (selected != null) {
            newDevices.stream()
                    .filter(d -> d.id().equals(selected.id()))
                    .findFirst()
                    .ifPresentOrElse(
                            d -> deviceList.getSelectionModel().select(d),
                            () -> deviceList.getSelectionModel().clearSelection());
        }
        updateSendButton();
    }

    // ---------------------------------------------------------------- file (F-03)

    @FXML
    private void onChooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Datei wählen");
        if (selectedFile != null && selectedFile.getParent() != null) {
            chooser.setInitialDirectory(selectedFile.getParent().toFile());
        }
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            selectFile(file.toPath());
        }
    }

    /**
     * A single file can be dropped on the content area (same effect as "Datei wählen").
     * Folders and several files at once are not accepted, Aether sends one file per transfer.
     */
    private void setUpFileDrop() {
        contentArea.setOnDragOver(e -> {
            if (droppableFile(e.getDragboard()) != null) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        contentArea.setOnDragEntered(e -> {
            if (droppableFile(e.getDragboard()) != null) {
                contentArea.getStyleClass().add("drop-target");
            }
            e.consume();
        });
        contentArea.setOnDragExited(e -> {
            contentArea.getStyleClass().remove("drop-target");
            e.consume();
        });
        contentArea.setOnDragDropped(e -> {
            File file = droppableFile(e.getDragboard());
            if (file != null) {
                tabs.selectToggle(uploadTab);
                selectFile(file.toPath());
            }
            e.setDropCompleted(file != null);
            e.consume();
        });
    }

    /** The dragged file, or null if it is not exactly one regular file. */
    private static File droppableFile(Dragboard board) {
        if (!board.hasFiles() || board.getFiles().size() != 1) {
            return null;
        }
        File file = board.getFiles().getFirst();
        return file.isFile() ? file : null;
    }

    private void selectFile(Path file) {
        if (hashTask != null) {
            hashTask.cancel();
        }
        selectedFile = file;
        selectedFileInfo = null;

        fileNameLabel.setText(file.getFileName().toString());
        fileSizeLabel.setText(formatSize(file.toFile().length()));
        hashField.setText("wird berechnet …");
        setShown(fileInfoGrid, true);
        setShown(hashProgress, true);
        updateSendButton();

        Task<FileInfo> task = new Task<>() {
            @Override
            protected FileInfo call() throws Exception {
                return FileInfo.fromFile(file);
            }
        };
        // Task handlers run on the FX thread.
        task.setOnSucceeded(e -> {
            if (task != hashTask) {
                return;
            }
            FileInfo info = task.getValue();
            selectedFileInfo = info;
            fileSizeLabel.setText(formatSize(info.size()));
            hashField.setText(HexFormat.of().formatHex(info.sha256()));
            setShown(hashProgress, false);
            updateSendButton();
        });
        task.setOnFailed(e -> {
            if (task != hashTask) {
                return;
            }
            Throwable error = task.getException();
            hashField.setText("Fehler: " + (error == null ? "unbekannt" : error.getMessage()));
            setShown(hashProgress, false);
            selectedFile = null;
            updateSendButton();
        });
        hashTask = task;
        hashExecutor.submit(task);
    }

    @FXML
    private void onSend() {
        Device target = deviceList.getSelectionModel().getSelectedItem();
        if (target == null || selectedFile == null || selectedFileInfo == null) {
            return;
        }
        Transfer transfer = backend.sendFile(target, selectedFile, selectedFileInfo);
        currentTransferId = transfer.getId();
        showTransfer(transfer);
    }

    /** "Senden" is only active when a device is selected and the file's hash is ready. */
    private void updateSendButton() {
        boolean ready = deviceList.getSelectionModel().getSelectedItem() != null
                && selectedFile != null
                && selectedFileInfo != null;
        sendButton.setDisable(!ready);
    }

    private void showTransfer(Transfer transfer) {
        if (!transfer.getId().equals(currentTransferId)) {
            return;
        }
        setShown(transferBox, true);
        String peer = transfer.getPeerName();
        String text = switch (transfer.getStatus()) {
            case REQUESTED -> "Warte auf Bestätigung von " + peer + " …"
                    + (transfer.getSecurityCode() == null ? "" : "\nSicherheitscode: " + transfer.getSecurityCode());
            case ACCEPTED -> peer + " hat angenommen.";
            case RUNNING -> String.format(Locale.ROOT, "Sende an %s … %d %%", peer,
                    Math.round(transfer.getProgress() * 100));
            case COMPLETED -> "Datei an " + peer + " gesendet.";
            case REJECTED -> peer + " hat abgelehnt." + detail(transfer);
            case CANCELLED -> "Übertragung abgebrochen." + detail(transfer);
            case FAILED -> "Übertragung fehlgeschlagen." + detail(transfer);
        };
        transferLabel.setText(text);
        transferProgress.setProgress(transfer.getProgress());
        TransferStatus status = transfer.getStatus();
        setShown(transferProgress, status == TransferStatus.RUNNING || status == TransferStatus.COMPLETED);
    }

    private static String detail(Transfer transfer) {
        return transfer.getMessage() == null ? "" : " (" + transfer.getMessage() + ")";
    }

    // ---------------------------------------------------------------- tabs and window

    private void setUpTabs() {
        // A tab can't be "unselected" by clicking it again.
        tabs.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                tabs.selectToggle(old);
                return;
            }
            boolean upload = now == uploadTab;
            uploadPane.setVisible(upload);
            placeholderPane.setVisible(!upload);
            if (now == requestsTab) {
                placeholderPane.setText("Anfragen (F-04 / F-05) folgen.");
            } else if (now == progressTab) {
                placeholderPane.setText("Fortschritt (F-07) folgt.");
            } else if (now == settingsTab) {
                placeholderPane.setText("Einstellungen folgen.");
            }
        });
    }

    @FXML
    private void onMinimize() {
        stage.setIconified(true);
    }

    @FXML
    private void onMaximize() {
        stage.setMaximized(!stage.isMaximized());
    }

    @FXML
    private void onClose() {
        stage.close();
    }

    private void makeDraggable(Node handle) {
        handle.setOnMousePressed(e -> {
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        handle.setOnMouseDragged(e -> {
            if (!stage.isMaximized()) {
                stage.setX(e.getScreenX() - dragOffsetX);
                stage.setY(e.getScreenY() - dragOffsetY);
            }
        });
        handle.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                onMaximize();
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    /** Binary units as in the concept: 1 MiB = 1024 KiB. */
    static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s (%,d Bytes)", value, units[unit], bytes).replace(',', '\'');
    }
}
