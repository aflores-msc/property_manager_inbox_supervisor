"""Configuration loading via Pydantic Settings and python-dotenv."""

from pydantic_settings import BaseSettings, SettingsConfigDict

from src.paths import get_app_file


class AppSettings(BaseSettings):
    """Application settings loaded from environment variables / .env file.

    The ``.env`` file is read from the user-level application directory
    (``~/.property_manager_ai/.env``) so the app works identically when run
    from source or from a packaged macOS ``.app`` / Windows ``.exe`` bundle.
    """

    model_config = SettingsConfigDict(
        env_file=str(get_app_file(".env")),
        env_file_encoding="utf-8",
        extra="ignore",
    )

    GOOGLE_API_KEY: str
    GEMINI_MODEL_NAME: str = "gemini-2.5-flash"
    MANAGER_EMAIL: str
    EMAIL_USERNAME: str


config = AppSettings()  # type: ignore[call-arg]
