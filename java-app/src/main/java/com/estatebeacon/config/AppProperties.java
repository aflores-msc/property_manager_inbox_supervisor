package com.estatebeacon.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Equivalent of the Python app's {@code AppSettings.MANAGER_EMAIL} /
 * {@code EMAIL_USERNAME} fields. Neither is read anywhere else in the Python
 * codebase either — they exist only as part of the {@code .env} contract.
 */
@ConfigurationProperties(prefix = "estate-beacon")
public record AppProperties(String managerEmail, String emailUsername) {
}
