"""Application entry point for Estate Beacon."""

from __future__ import annotations

import logging
import sys

from PyQt6.QtWidgets import QApplication

from src.ui import InboxSupervisorWindow


def main() -> None:
    """Launch the Estate Beacon desktop application."""
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
