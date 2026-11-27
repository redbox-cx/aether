package ch.redlab.aether.ui;

import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shows a simple SVG file (viewBox plus filled {@code <path>} elements) as crisp vector shapes.
 * JavaFX cannot load SVG images, so the paths are turned into {@link SVGPath} nodes. Use it in FXML:
 * {@code <SvgIcon source="images/logo.svg" size="52"/>}. {@code source} is relative to this package.
 */
public class SvgIcon extends StackPane {

    private static final Pattern VIEW_BOX = Pattern.compile("viewBox=\"[0-9.]+ +[0-9.]+ +([0-9.]+) +([0-9.]+)\"");
    private static final Pattern PATH = Pattern.compile("<path [^>]*>");
    private static final Pattern FILL = Pattern.compile("fill=\"([^\"]+)\"");
    private static final Pattern DATA = Pattern.compile(" d=\"([^\"]+)\"");

    private String source;
    private double size = 24;

    public SvgIcon() {
        setMouseTransparent(true);
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
        rebuild();
    }

    public double getSize() {
        return size;
    }

    public void setSize(double size) {
        this.size = size;
        rebuild();
    }

    private void rebuild() {
        getChildren().clear();
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        if (source == null) {
            return;
        }
        String svg = read(source);
        Matcher box = VIEW_BOX.matcher(svg);
        if (svg.isEmpty() || !box.find()) {
            return;
        }
        double width = Double.parseDouble(box.group(1));
        double height = Double.parseDouble(box.group(2));

        Group shapes = new Group();
        Matcher paths = PATH.matcher(svg);
        while (paths.find()) {
            String tag = paths.group();
            Matcher data = DATA.matcher(tag);
            if (!data.find()) {
                continue;
            }
            SVGPath path = new SVGPath();
            path.setContent(data.group(1));
            path.setFillRule(FillRule.EVEN_ODD);
            Matcher fill = FILL.matcher(tag);
            path.setFill(fill.find() ? Color.web(fill.group(1)) : Color.BLACK);
            shapes.getChildren().add(path);
        }
        double scale = size / Math.max(width, height);
        shapes.setScaleX(scale);
        shapes.setScaleY(scale);
        getChildren().add(shapes);
    }

    private String read(String resource) {
        try (InputStream in = SvgIcon.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.getLogger(SvgIcon.class.getName())
                        .log(System.Logger.Level.WARNING, "SVG resource not found: " + resource);
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.getLogger(SvgIcon.class.getName())
                    .log(System.Logger.Level.WARNING, "Cannot read SVG resource " + resource, e);
            return "";
        }
    }
}
