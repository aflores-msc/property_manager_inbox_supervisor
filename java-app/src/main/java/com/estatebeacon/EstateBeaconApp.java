package com.estatebeacon;

import atlantafx.base.theme.PrimerDark;
import com.estatebeacon.ui.DashboardView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point. Bootstraps a headless Spring context in {@link #init()} (so
 * Gmail/LangGraph4j/Spring AI beans are wired the normal Spring Boot way),
 * then hands control to JavaFX for the actual window.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EstateBeaconApp extends Application {

    private ConfigurableApplicationContext springContext;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() {
        springContext = new SpringApplicationBuilder(EstateBeaconApp.class)
                .headless(false)
                .run(getParameters().getRaw().toArray(new String[0]));
    }

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        springContext.getBean(DashboardView.class).show(stage);
    }

    @Override
    public void stop() {
        springContext.close();
        Platform.exit();
    }
}
