package com.estatebeacon.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cross-platform application data directory, shared with the Python implementation
 * ({@code ~/.property_manager_ai}) so both clients can point at the same Gmail
 * OAuth credentials, {@code .env}, and SQLite database.
 */
public final class AppPaths {

    private static final String APP_DIR_NAME = ".property_manager_ai";

    private AppPaths() {
    }

    public static Path appDir() {
        Path dir = Path.of(System.getProperty("user.home"), APP_DIR_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create " + dir, e);
        }
        return dir;
    }

    public static Path appFile(String filename) {
        return appDir().resolve(filename);
    }
}
