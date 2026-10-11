package com.estatebeacon.ui;

import com.estatebeacon.config.AppPaths;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads/writes {@code ~/.property_manager_ai/ui-state.json}, the same directory {@link AppPaths} uses for everything else. */
@Component
public class UiStateStore {

    private static final Logger log = LoggerFactory.getLogger(UiStateStore.class);
    private static final String FILE_NAME = "ui-state.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<UiState> load() {
        Path file = AppPaths.appFile(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(file.toFile(), UiState.class));
        } catch (IOException e) {
            log.warn("Failed to read UI state from {}, ignoring saved state", file, e);
            return Optional.empty();
        }
    }

    public void save(UiState state) {
        Path file = AppPaths.appFile(FILE_NAME);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), state);
        } catch (IOException e) {
            log.warn("Failed to write UI state to {}", file, e);
        }
    }
}
