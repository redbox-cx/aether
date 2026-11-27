package ch.redlab.aether.ui;

import ch.redlab.aether.model.Device;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;

/**
 * Device card from the Figma design (8:141 and following): monitor icon, name,
 * IP address and a status dot on the right. Looks are defined in main-view.css.
 * Sizes are about 1.3 times the Figma values so the text stays readable.
 */
class DeviceCell extends ListCell<Device> {

    /** Monitor icon from Figma ("Monitor", 18 x 18 instance), drawn as an SVG path. */
    private static final String MONITOR_ICON =
            "M4.8 12.8H10.1333M7.46667 10.1333V12.8M2.13333 0.8H12.8C13.5364 0.8 14.1333 1.39695 14.1333 2.13333"
                    + "V8.8C14.1333 9.53638 13.5364 10.1333 12.8 10.1333H2.13333C1.39695 10.1333 0.8 9.53638 0.8 8.8"
                    + "V2.13333C0.8 1.39695 1.39695 0.8 2.13333 0.8Z";

    /** The Figma icon is 18 px; it is drawn 30 % larger to match the larger text. */
    private static final double ICON_SCALE = 1.3;

    private final HBox card = new HBox();
    private final Label name = new Label();
    private final Label address = new Label();

    DeviceCell() {
        SVGPath icon = new SVGPath();
        icon.setContent(MONITOR_ICON);
        icon.getStyleClass().add("device-icon");
        icon.setScaleX(ICON_SCALE);
        icon.setScaleY(ICON_SCALE);
        StackPane iconBox = new StackPane(icon);
        iconBox.setMinSize(24, 24);
        iconBox.setPrefSize(24, 24);
        iconBox.setMaxSize(24, 24);

        name.getStyleClass().add("device-name");
        address.getStyleClass().add("device-address");
        VBox text = new VBox(name, address);
        text.setAlignment(Pos.CENTER_LEFT);
        text.getStyleClass().add("device-text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Region dot = new Region();
        dot.getStyleClass().add("status-dot");
        dot.setMinSize(12, 12);
        dot.setPrefSize(12, 12);
        dot.setMaxSize(12, 12);

        card.getChildren().addAll(iconBox, text, spacer, dot);
        card.setAlignment(Pos.CENTER_LEFT);
        card.getStyleClass().add("device-card");
        // Height comes from the CSS, the width follows the list (cards grow with the sidebar).
        card.setMinWidth(0);
        card.prefWidthProperty().bind(widthProperty().subtract(2));

        getStyleClass().add("device-cell");
        setText(null);
    }

    @Override
    protected void updateItem(Device device, boolean empty) {
        super.updateItem(device, empty);
        if (empty || device == null) {
            setGraphic(null);
        } else {
            name.setText(device.name());
            address.setText(device.address().getHostAddress());
            setGraphic(card);
        }
    }
}
