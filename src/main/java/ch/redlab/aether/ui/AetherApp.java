package ch.redlab.aether.ui;

import ch.redlab.aether.core.AetherBackend;
import ch.redlab.aether.core.DemoBackend;
import ch.redlab.aether.core.NetworkBackend;
import ch.redlab.aether.core.Settings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.InputStream;

public class AetherApp extends Application {

    /** Start size, about 1.3 times the Figma frame (789 x 480). */
    private static final double WIDTH = 1040;
    private static final double HEIGHT = 640;
    /** Smallest size, still fits all four tabs and the upload form. */
    private static final double MIN_WIDTH = 860;
    private static final double MIN_HEIGHT = 540;

    private AetherBackend backend;
    private MainController controller;

    @Override
    public void start(Stage stage) throws Exception {
        boolean sansation = loadBundledFonts();

        // Real network backend; start with -Daether.demo=true to work on the UI without a network.
        Settings settings = Settings.load();
        backend = Boolean.getBoolean("aether.demo") ? new DemoBackend(settings) : new NetworkBackend(settings);
        controller = new MainController(backend);

        FXMLLoader loader = new FXMLLoader(AetherApp.class.getResource("main-view.fxml"));
        loader.setController(controller);
        Parent root = loader.load();
        if (sansation) {
            root.getStyleClass().add("sansation");
        }
        controller.setStatusBar(createStatusBar());

        // Normal Windows window frame: opaque scene, so text keeps ClearType smoothing (a transparent window
        // makes JavaFX fall back to blurry grayscale text).
        root.getStyleClass().add("native-frame");
        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#f3f3f3"));
        scene.getStylesheets().add(AetherApp.class.getResource("main-view.css").toExternalForm());

        stage.setScene(scene);
        stage.setTitle("Aether");
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.getIcons().add(new Image(AetherApp.class.getResource("images/logo.png").toExternalForm()));
        controller.attach(stage);
        stage.show();

        try {
            backend.start();
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                    "Aether could not start:\n" + e.getMessage());
            alert.setHeaderText("Start failed");
            alert.showAndWait();
            Platform.exit();
        }
    }

    /** Called by JavaFX when the last window is closed: stop all backend threads and sockets. */
    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    /** Temporary status bar (Figma 20:9) until the StatusBar class is taken over. */
    private static HBox createStatusBar() {
        HBox bar = new HBox(new Label("0 MiB/s"));
        bar.setMinWidth(0);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("status-bar-placeholder");
        return bar;
    }

    /**
     * Loads Sansation from resources/ch/redlab/aether/ui/fonts if the files are there.
     *
     * @return true if the "Sansation" family is available (bundled or installed)
     */
    private static boolean loadBundledFonts() {
        for (String file : new String[] {"Sansation-Regular.ttf", "Sansation-Light.ttf", "Sansation-Bold.ttf"}) {
            try (InputStream in = AetherApp.class.getResourceAsStream("fonts/" + file)) {
                if (in != null) {
                    Font.loadFont(in, 12);
                }
            } catch (IOException ignored) {
                // Missing font only means the Segoe UI fallback is used.
            }
        }
        return Font.getFamilies().contains("Sansation");
    }
}
