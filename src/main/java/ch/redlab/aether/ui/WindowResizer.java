package ch.redlab.aether.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import java.util.function.Predicate;

/**
 * Lets the user resize an undecorated stage by dragging its edges and corners.
 * <p>
 * Works with event filters on the scene, so it does not interfere with the controls.
 * Fully transparent pixels of a TRANSPARENT stage receive no mouse events, so the
 * "top edge" of the right part is the upper border of the grey header; {@code topEdgeAt}
 * tells where that border is for a given x.
 */
final class WindowResizer {

    /** Grab width of the edges in px. */
    private static final double BORDER = 6;

    private final Stage stage;
    private final Scene scene;
    private final TopEdge topEdge;
    private final Predicate<Node> excluded;

    private Cursor cursor = Cursor.DEFAULT;
    private boolean resizing;
    private double startScreenX;
    private double startScreenY;
    private Rectangle2D startBounds;

    /** Y coordinate (scene) where the visible top border starts for a given x. */
    @FunctionalInterface
    interface TopEdge {
        double at(double sceneX);
    }

    private WindowResizer(Stage stage, TopEdge topEdge, Predicate<Node> excluded) {
        this.stage = stage;
        this.scene = stage.getScene();
        this.topEdge = topEdge;
        this.excluded = excluded;
    }

    /**
     * @param topEdge  where the top border is for a given x
     * @param excluded nodes that keep their own mouse handling (e.g. the window buttons)
     */
    static void install(Stage stage, TopEdge topEdge, Predicate<Node> excluded) {
        WindowResizer resizer = new WindowResizer(stage, topEdge, excluded);
        Scene scene = stage.getScene();
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, resizer::onMoved);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, resizer::onPressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, resizer::onDragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, resizer::onReleased);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
            if (!resizer.resizing) {
                resizer.setCursor(Cursor.DEFAULT);
            }
        });
    }

    private void onMoved(MouseEvent e) {
        if (resizing) {
            return;
        }
        setCursor(stage.isMaximized() || isExcluded(e) ? Cursor.DEFAULT : cursorAt(e.getSceneX(), e.getSceneY()));
    }

    private void onPressed(MouseEvent e) {
        if (cursor == Cursor.DEFAULT || !e.isPrimaryButtonDown()) {
            return;
        }
        resizing = true;
        startScreenX = e.getScreenX();
        startScreenY = e.getScreenY();
        startBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (!resizing) {
            return;
        }
        double dx = e.getScreenX() - startScreenX;
        double dy = e.getScreenY() - startScreenY;
        double x = startBounds.getMinX();
        double y = startBounds.getMinY();
        double w = startBounds.getWidth();
        double h = startBounds.getHeight();
        double minW = stage.getMinWidth();
        double minH = stage.getMinHeight();

        if (cursor == Cursor.E_RESIZE || cursor == Cursor.NE_RESIZE || cursor == Cursor.SE_RESIZE) {
            w = Math.max(minW, w + dx);
        }
        if (cursor == Cursor.S_RESIZE || cursor == Cursor.SE_RESIZE || cursor == Cursor.SW_RESIZE) {
            h = Math.max(minH, h + dy);
        }
        if (cursor == Cursor.W_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.SW_RESIZE) {
            double newW = Math.max(minW, w - dx);
            x += w - newW;
            w = newW;
        }
        if (cursor == Cursor.N_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.NE_RESIZE) {
            double newH = Math.max(minH, h - dy);
            y += h - newH;
            h = newH;
        }
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
        e.consume();
    }

    private void onReleased(MouseEvent e) {
        if (resizing) {
            resizing = false;
            e.consume();
        }
    }

    private Cursor cursorAt(double x, double y) {
        double width = scene.getWidth();
        double height = scene.getHeight();
        double top = topEdge.at(x);

        boolean left = x < BORDER;
        boolean right = x > width - BORDER;
        boolean bottom = y > height - BORDER;
        boolean upper = y >= top && y < top + BORDER;

        if (upper && left) return Cursor.NW_RESIZE;
        if (upper && right) return Cursor.NE_RESIZE;
        if (bottom && left) return Cursor.SW_RESIZE;
        if (bottom && right) return Cursor.SE_RESIZE;
        if (upper) return Cursor.N_RESIZE;
        if (bottom) return Cursor.S_RESIZE;
        if (left) return Cursor.W_RESIZE;
        if (right) return Cursor.E_RESIZE;
        return Cursor.DEFAULT;
    }

    private boolean isExcluded(MouseEvent e) {
        Object target = e.getTarget();
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (excluded.test(n)) {
                return true;
            }
        }
        return false;
    }

    private void setCursor(Cursor next) {
        if (cursor != next) {
            cursor = next;
            scene.setCursor(next);
        }
    }
}
