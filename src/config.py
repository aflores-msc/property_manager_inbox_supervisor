"""Configuration loading via Pydantic Settings and python-dotenv."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class AppSettings(BaseSettings):
    """Application settings loaded from environment variables / .env file."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    GOOGLE_API_KEY: str
    GEMINI_MODEL_NAME: str = "gemini-2.5-flash"
    MANAGER_EMAIL: str
    EMAIL_USERNAME: str


config = AppSettings()  # type: ignore[call-arg]
