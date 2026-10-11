package com.estatebeacon;

import atlantafx.base.theme.PrimerDark;
import com.estatebeacon.ui.DashboardView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Entry point. Bootstraps a headless Spring context in {@link #init()} (so
 * Gmail/LangGraph4j/Spring AI beans are wired the normal Spring Boot way),
 * then hands control to JavaFX for the actual window.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EstateBeaconApp extends Application {

    private static final Logger log = LoggerFactory.getLogger(EstateBeaconApp.class);
    private static final String APP_ICON_RESOURCE = "/app-icon.png";

    private ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        setDockIcon();
        springContext = new SpringApplicationBuilder(EstateBeaconApp.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        var iconUrl = getClass().getResource(APP_ICON_RESOURCE);
        if (iconUrl != null) {
            stage.getIcons().add(new Image(iconUrl.toExternalForm()));
        }
        springContext.getBean(DashboardView.class).show(stage);
    }

    /**
     * Replaces macOS's generic launcher icon (a plain {@code java} process otherwise shows up in
     * the Dock as a bare "exec" tile) with the app's own icon. {@code Stage.getIcons()} alone
     * doesn't do this on macOS — it only affects the window's own decoration — so this uses the
     * cross-platform {@link Taskbar} API (JEP 272) instead, which does control the Dock/taskbar
     * icon on every platform that supports it.
     */
    private static void setDockIcon() {
        if (!Taskbar.isTaskbarSupported()) {
            return;
        }
        Taskbar taskbar = Taskbar.getTaskbar();
        if (!taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
            return;
        }
        try (var in = EstateBeaconApp.class.getResourceAsStream(APP_ICON_RESOURCE)) {
            if (in == null) {
                log.warn("App icon resource {} not found on classpath", APP_ICON_RESOURCE);
                return;
            }
            BufferedImage icon = ImageIO.read(in);
            taskbar.setIconImage(icon);
        } catch (IOException e) {
            log.warn("Failed to load app icon", e);
        }
    }

    @Override
    public void stop() {
        springContext.close();
        Platform.exit();
    }
}
