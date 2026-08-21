package roommount;

import gearth.extensions.ExtensionForm;
import gearth.extensions.ExtensionFormCreator;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class RoomMountLauncher extends ExtensionFormCreator {

    @Override
    protected ExtensionForm createForm(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/roommount/roommount.fxml"));
        Parent root = loader.load();
        String ver = RoomMount.class.getAnnotation(gearth.extensions.ExtensionInfo.class).Version();
        primaryStage.setTitle("Room Backup " + ver);
        try {
            var url = getClass().getResource("/roommount/icon.png");
            if (url != null) {
                primaryStage.getIcons().add(new Image(url.toExternalForm()));
            }
        } catch (Exception ignored) {
        }
        primaryStage.setScene(new Scene(root));
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(420);
        primaryStage.setMinHeight(480);
        return loader.getController();
    }

    public static void main(String[] args) {
        runExtensionForm(args, RoomMountLauncher.class);
    }
}
