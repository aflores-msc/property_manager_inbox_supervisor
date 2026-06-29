package com.estatedevin.codemcp.ollama;

import java.time.Duration;

final class OllamaUtils {

    private OllamaUtils() {
    }

    static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static int toSafeMillis(Duration duration) {
        long millis = duration.toMillis();
        if (millis > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, Math.toIntExact(millis));
    }
}
