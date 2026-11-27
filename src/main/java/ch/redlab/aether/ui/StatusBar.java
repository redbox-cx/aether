package ch.redlab.aether.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Status bar below the content (Figma 20:9): current total speed and number of active transfers. */
public class StatusBar extends HBox {

    private final Label speed = new Label(TransferText.speed(0));
    private final Label active = new Label(TransferText.activeSummary(0));

    public StatusBar() {
        getStyleClass().add("status-bar");
        setMinWidth(0);
        setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(speed, spacer, active);
    }

    public void show(double bytesPerSecond, int activeTransfers) {
        speed.setText(TransferText.speed(bytesPerSecond));
        active.setText(TransferText.activeSummary(activeTransfers));
    }
}
