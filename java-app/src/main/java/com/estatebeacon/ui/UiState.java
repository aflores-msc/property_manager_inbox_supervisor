package com.estatebeacon.ui;

import java.util.Map;

/** Persisted window geometry and table column widths, saved to {@code ui-state.json}. */
public record UiState(
        double windowX,
        double windowY,
        double windowWidth,
        double windowHeight,
        boolean maximized,
        Map<String, Double> columnWidths
) {
}
