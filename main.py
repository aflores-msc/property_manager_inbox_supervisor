"""Application entry point for the Property Manager Inbox Supervisor."""

from __future__ import annotations

import logging
import sys

from PyQt6.QtWidgets import QApplication

from src.ui import InboxSupervisorWindow


def main() -> None:
    """Launch the Inbox Supervisor desktop application."""
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
    )

    app = QApplication(sys.argv)
    window = InboxSupervisorWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
