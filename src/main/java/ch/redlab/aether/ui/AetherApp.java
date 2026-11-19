package ch.redlab.aether.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class AetherApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        FXMLLoader loader = new FXMLLoader(AetherApp.class.getResource("main-view.fxml"));
        stage.setScene(new Scene(loader.load(), 800, 500));
        stage.setTitle("Aether");
        stage.show();
    }
}
