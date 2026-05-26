"""PyQt6 monitoring dashboard with QThread workers."""

from __future__ import annotations

import json
import logging
from typing import Any

from PyQt6.QtCore import QThread, pyqtSignal, Qt
from PyQt6.QtGui import QColor, QFont
from PyQt6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMainWindow,
    QProgressBar,
    QPushButton,
    QSplitter,
    QStatusBar,
    QTableWidget,
    QTableWidgetItem,
    QTextEdit,
    QVBoxLayout,
    QWidget,
)

from src.models import PropertyManagementState

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Dark-mode stylesheet
# ---------------------------------------------------------------------------

DARK_STYLE = """
QMainWindow, QWidget {
    background-color: #1e1e2e;
    color: #cdd6f4;
    font-family: 'Segoe UI', 'Roboto', sans-serif;
}
QTableWidget {
    background-color: #181825;
    alternate-background-color: #1e1e2e;
    gridline-color: #313244;
    border: 1px solid #313244;
    border-radius: 6px;
    selection-background-color: #45475a;
    selection-color: #cdd6f4;
}
QTableWidget::item {
    padding: 6px;
}
QHeaderView::section {
    background-color: #313244;
    color: #cdd6f4;
    font-weight: bold;
    padding: 6px;
    border: none;
    border-bottom: 2px solid #89b4fa;
}
QPushButton {
    background-color: #89b4fa;
    color: #1e1e2e;
    font-weight: bold;
    padding: 10px 24px;
    border: none;
    border-radius: 6px;
}
QPushButton:hover {
    background-color: #74c7ec;
}
QPushButton:disabled {
    background-color: #45475a;
    color: #6c7086;
}
QTextEdit {
    background-color: #181825;
    color: #a6e3a1;
    border: 1px solid #313244;
    border-radius: 6px;
    padding: 8px;
    font-family: 'Cascadia Code', 'Consolas', monospace;
    font-size: 13px;
}
QStatusBar {
    background-color: #11111b;
    color: #a6adc8;
}
QProgressBar {
    background-color: #313244;
    border: none;
    border-radius: 4px;
    text-align: center;
    color: #1e1e2e;
    font-weight: bold;
}
QProgressBar::chunk {
    background-color: #89b4fa;
    border-radius: 4px;
}
QLabel#title {
    font-size: 20px;
    font-weight: bold;
    color: #cdd6f4;
}
"""

# Priority colour mapping
_PRIORITY_COLOURS: dict[str, str] = {
    "URGENT": "#f38ba8",
    "HIGH": "#fab387",
    "MEDIUM": "#f9e2af",
    "LOW": "#a6e3a1",
}

_CLASSIFICATION_COLOURS: dict[str, str] = {
    "CITY_NOTICE": "#cba6f7",
    "MAINTENANCE": "#89b4fa",
    "TENANT_DISPUTE": "#f38ba8",
}


# ---------------------------------------------------------------------------
# Worker threads
# ---------------------------------------------------------------------------

class FetchEmailsWorker(QThread):
    """Fetch unread emails from IMAP in a background thread."""

    finished = pyqtSignal(list)
    error = pyqtSignal(str)

    def run(self) -> None:
        try:
            from src.email_client import EmailFetcher

            fetcher = EmailFetcher()
            fetcher.connect()
            emails = fetcher.fetch_unread_emails()
            fetcher.disconnect()
            self.finished.emit(emails)
        except Exception as exc:
            logger.exception("Email fetch failed")
            self.error.emit(str(exc))


class ProcessEmailWorker(QThread):
    """Run a single email through the LangGraph pipeline."""

    finished = pyqtSignal(dict)
    error = pyqtSignal(str)

    def __init__(self, email_data: dict[str, str], parent: QThread | None = None) -> None:
        super().__init__(parent)
        self._email_data = email_data

    def run(self) -> None:
        try:
            from src.agents import build_graph

            graph = build_graph()
            initial_state: PropertyManagementState = {
                "original_email_subject": self._email_data.get("subject", ""),
                "original_email_sender": self._email_data.get("sender", ""),
                "original_email_text": self._email_data.get("body", ""),
            }
            result = graph.invoke(initial_state)
            self.finished.emit(dict(result))
        except Exception as exc:
            logger.exception("Email processing failed")
            self.error.emit(str(exc))


# ---------------------------------------------------------------------------
# Main window
# ---------------------------------------------------------------------------

class InboxSupervisorWindow(QMainWindow):
    """Property Management Inbox Supervisor dashboard."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Property Manager — Inbox Supervisor")
        self.setMinimumSize(1200, 700)

        self._processed_results: list[dict[str, Any]] = []
        self._pending_emails: list[dict[str, str]] = []
        self._current_worker: ProcessEmailWorker | None = None

        self._build_ui()
        self.setStyleSheet(DARK_STYLE)

    # ---- UI construction ---------------------------------------------------

    def _build_ui(self) -> None:
        central = QWidget()
        self.setCentralWidget(central)
        root_layout = QVBoxLayout(central)
        root_layout.setContentsMargins(16, 16, 16, 16)

        # Title bar
        title_bar = QHBoxLayout()
        title = QLabel("Inbox Supervisor")
        title.setObjectName("title")
        title_bar.addWidget(title)
        title_bar.addStretch()

        self._fetch_btn = QPushButton("Fetch New Emails")
        self._fetch_btn.clicked.connect(self._on_fetch_clicked)
        title_bar.addWidget(self._fetch_btn)
        root_layout.addLayout(title_bar)

        # Progress bar
        self._progress = QProgressBar()
        self._progress.setFixedHeight(6)
        self._progress.setTextVisible(False)
        self._progress.setVisible(False)
        root_layout.addWidget(self._progress)

        # Splitter: table | detail
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # Left: table
        self._table = QTableWidget()
        self._table.setColumnCount(4)
        self._table.setHorizontalHeaderLabels(
            ["Subject", "Classification", "Priority", "Property Address"]
        )
        self._table.setAlternatingRowColors(True)
        self._table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self._table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        header = self._table.horizontalHeader()
        if header is not None:
            header.setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
            header.setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(3, QHeaderView.ResizeMode.Stretch)
        self._table.currentCellChanged.connect(self._on_row_selected)
        splitter.addWidget(self._table)

        # Right: detail panel
        detail_widget = QWidget()
        detail_layout = QVBoxLayout(detail_widget)
        detail_layout.setContentsMargins(8, 0, 0, 0)
        detail_label = QLabel("Extracted Details")
        detail_label.setFont(QFont("Segoe UI", 14, QFont.Weight.Bold))
        detail_layout.addWidget(detail_label)
        self._detail_view = QTextEdit()
        self._detail_view.setReadOnly(True)
        detail_layout.addWidget(self._detail_view)
        splitter.addWidget(detail_widget)

        splitter.setSizes([700, 500])
        root_layout.addWidget(splitter)

        # Status bar
        self._status_bar = QStatusBar()
        self.setStatusBar(self._status_bar)
        self._status_bar.showMessage("Ready — click 'Fetch New Emails' to start.")

    # ---- Slots -------------------------------------------------------------

    def _on_fetch_clicked(self) -> None:
        self._fetch_btn.setEnabled(False)
        self._status_bar.showMessage("Connecting to mail server...")
        self._progress.setRange(0, 0)
        self._progress.setVisible(True)

        self._fetch_worker = FetchEmailsWorker()
        self._fetch_worker.finished.connect(self._on_emails_fetched)
        self._fetch_worker.error.connect(self._on_fetch_error)
        self._fetch_worker.start()

    def _on_emails_fetched(self, emails: list[dict[str, str]]) -> None:
        if not emails:
            self._status_bar.showMessage("No unread emails found.")
            self._progress.setVisible(False)
            self._fetch_btn.setEnabled(True)
            return

        self._pending_emails = list(emails)
        total = len(emails)
        self._progress.setRange(0, total)
        self._progress.setValue(0)
        self._status_bar.showMessage(f"Processing {total} email(s) through AI pipeline...")
        self._process_next_email()

    def _on_fetch_error(self, error_msg: str) -> None:
        self._status_bar.showMessage(f"Fetch error: {error_msg}")
        self._progress.setVisible(False)
        self._fetch_btn.setEnabled(True)

    def _process_next_email(self) -> None:
        if not self._pending_emails:
            self._progress.setVisible(False)
            self._fetch_btn.setEnabled(True)
            self._status_bar.showMessage(
                f"Done — {self._table.rowCount()} email(s) processed."
            )
            return

        email_data = self._pending_emails.pop(0)
        self._current_worker = ProcessEmailWorker(email_data)
        self._current_worker.finished.connect(
            lambda result, ed=email_data: self._on_email_processed(ed, result)
        )
        self._current_worker.error.connect(self._on_process_error)
        self._current_worker.start()

    def _on_email_processed(
        self, email_data: dict[str, str], result: dict[str, Any]
    ) -> None:
        self._processed_results.append(result)

        routing = result.get("routing_data")
        classification = routing.classification if routing else "UNKNOWN"
        priority = routing.priority_level if routing else "—"
        address = routing.property_address if routing else "—"
        subject = email_data.get("subject", "(no subject)")

        row = self._table.rowCount()
        self._table.insertRow(row)

        self._table.setItem(row, 0, QTableWidgetItem(subject))

        cls_item = QTableWidgetItem(classification)
        cls_item.setForeground(QColor(_CLASSIFICATION_COLOURS.get(classification, "#cdd6f4")))
        self._table.setItem(row, 1, cls_item)

        pri_item = QTableWidgetItem(priority)
        pri_item.setForeground(QColor(_PRIORITY_COLOURS.get(priority, "#cdd6f4")))
        self._table.setItem(row, 2, pri_item)

        self._table.setItem(row, 3, QTableWidgetItem(address))

        # Advance progress
        processed_count = len(self._processed_results)
        self._progress.setValue(processed_count)
        self._status_bar.showMessage(
            f"Processed {processed_count} / "
            f"{processed_count + len(self._pending_emails)} email(s)..."
        )
        self._process_next_email()

    def _on_process_error(self, error_msg: str) -> None:
        self._status_bar.showMessage(f"Processing error: {error_msg}")
        processed_count = len(self._processed_results)
        self._progress.setValue(processed_count)
        self._process_next_email()

    def _on_row_selected(self, row: int, _col: int, _prev_row: int, _prev_col: int) -> None:
        if 0 <= row < len(self._processed_results):
            result = self._processed_results[row]
            detail = self._format_result(result)
            self._detail_view.setText(detail)

    # ---- Helpers -----------------------------------------------------------

    @staticmethod
    def _format_result(result: dict[str, Any]) -> str:
        """Format a pipeline result dict as readable JSON for the detail panel."""
        output: dict[str, Any] = {}

        routing = result.get("routing_data")
        if routing is not None:
            output["routing"] = routing.model_dump()

        for key in ("city_notice", "maintenance", "dispute"):
            value = result.get(key)
            if value is not None:
                output[key] = value.model_dump()

        error = result.get("error")
        if error:
            output["error"] = error

        return json.dumps(output, indent=2, default=str)
