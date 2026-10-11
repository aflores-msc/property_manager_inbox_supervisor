"""Application entry point for Estate Beacon."""

from __future__ import annotations

import logging
import sys

from PyQt6.QtWidgets import QApplication, QMessageBox


def main() -> None:
    """Launch the Estate Beacon desktop application."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    app = QApplication(sys.argv)

    # src.config.AppSettings() validates required environment variables
    # (GOOGLE_API_KEY) at import time — but nothing imports src.config until
    # the first email is actually processed, deep inside a background
    # QThread's broad except Exception. Importing it here instead means a
    # missing/invalid .env fails immediately, with a message the user can act
    # on, rather than surfacing later as a cryptic pydantic ValidationError in
    # the status bar after a fetch has already run.
    try:
        import src.config  # noqa: F401
    except Exception as exc:
        QMessageBox.critical(
            None,
            "Estate Beacon — Configuration Error",
            "Estate Beacon could not start because its configuration is "
            "missing or invalid.\n\n"
            f"{exc}\n\n"
            "Create a .env file at ~/.property_manager_ai/.env with at least "
            "GOOGLE_API_KEY set, then restart the app.",
        )
        sys.exit(1)

    from src.ui import InboxSupervisorWindow

    window = InboxSupervisorWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
