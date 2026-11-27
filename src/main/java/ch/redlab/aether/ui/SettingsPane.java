package ch.redlab.aether.ui;

import ch.redlab.aether.core.Settings;
import ch.redlab.aether.protocol.ProtocolConstants;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings tab: name of this device and folder for received files (Konzeptbericht 2.1, the only stored data).
 * Changes apply right away: the new name goes out with the next HELLO, the folder is used for the next file.
 */
public class SettingsPane extends VBox {

    private final TextField nameField = new TextField();
    private final Label folderLabel = new Label();
    private final Label feedback = new Label();
    private Settings settings;
    private Path folder;

    public SettingsPane() {
        super(14);
        getStyleClass().add("upload-pane");
        setAlignment(Pos.TOP_CENTER);

        VBox card = new VBox(12);
        card.getStyleClass().add("request-card");
        card.setMaxWidth(560);

        Label title = new Label("This device");
        title.getStyleClass().add("request-title");

        nameField.setPromptText("Name shown to other devices");
        Button chooseFolder = new Button("Choose folder");
        chooseFolder.getStyleClass().add("aero-button");
        chooseFolder.setOnAction(e -> chooseFolder());
        folderLabel.getStyleClass().add("info-value");
        folderLabel.setWrapText(true);
        HBox folderRow = new HBox(10, folderLabel, chooseFolder);
        folderRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(folderLabel, Priority.ALWAYS);
        folderLabel.setMaxWidth(Double.MAX_VALUE);

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        form.getColumnConstraints().addAll(new ColumnConstraints(110), new ColumnConstraints());
        form.getColumnConstraints().get(1).setHgrow(Priority.ALWAYS);
        form.add(key("Device name"), 0, 0);
        form.add(nameField, 1, 0);
        form.add(key("Save files in"), 0, 1);
        form.add(folderRow, 1, 1);

        Button save = new Button("Save");
        save.getStyleClass().addAll("aero-button", "accept-button");
        save.setOnAction(e -> save());
        feedback.getStyleClass().add("transfer-label");
        HBox actions = new HBox(10, feedback, save);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Label ports = new Label(String.format("Network: discovery on UDP %d, transfers on TCP %d. "
                        + "Both must be allowed in the firewall.",
                ProtocolConstants.DISCOVERY_PORT, ProtocolConstants.TRANSFER_PORT));
        ports.getStyleClass().add("hint-label");
        ports.setWrapText(true);

        card.getChildren().addAll(title, form, actions);
        VBox info = new VBox(ports);
        info.setMaxWidth(560);
        getChildren().addAll(card, info);
    }

    /** Shows the current values; called once by the controller. */
    public void setSettings(Settings settings) {
        this.settings = settings;
        nameField.setText(settings.getDeviceName());
        folder = settings.getDownloadDir();
        folderLabel.setText(folder.toString());
        feedback.setText("");
    }

    private void chooseFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Folder for received files");
        File current = folder == null ? null : folder.toFile();
        if (current != null && current.isDirectory()) {
            chooser.setInitialDirectory(current);
        }
        File chosen = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (chosen != null) {
            folder = chosen.toPath();
            folderLabel.setText(folder.toString());
            feedback.setText("");
        }
    }

    private void save() {
        String error = DeviceName.validate(nameField.getText());
        if (error != null) {
            feedback.setText(error);
            return;
        }
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            feedback.setText("Folder cannot be used: " + e.getMessage());
            return;
        }
        settings.setDeviceName(nameField.getText().strip());
        settings.setDownloadDir(folder);
        settings.save();
        nameField.setText(settings.getDeviceName());
        feedback.setText("Saved.");
    }

    private static Label key(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("info-key");
        return label;
    }
}
