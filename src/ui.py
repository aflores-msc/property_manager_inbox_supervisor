"""PyQt6 monitoring dashboard with QThread workers."""

from __future__ import annotations

import csv
import json
import logging
from datetime import datetime
from typing import Any

from PyQt6.QtCore import QSettings, QThread, QTimer, pyqtSignal, Qt
from PyQt6.QtGui import QColor, QFont
from PyQt6.QtWidgets import (
    QApplication,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFormLayout,
    QHBoxLayout,
    QHeaderView,
    QLabel,
    QMainWindow,
    QMessageBox,
    QProgressBar,
    QPushButton,
    QSpinBox,
    QSplitter,
    QStatusBar,
    QTableWidget,
    QTableWidgetItem,
    QTextBrowser,
    QVBoxLayout,
    QWidget,
)

from src.database import Database
from src.models import PropertyManagementState

logger = logging.getLogger(__name__)

# QSettings organisation / application identifiers.
_SETTINGS_ORG = "PropertyManager"
_SETTINGS_APP = "InboxSupervisor"
_SETTINGS_INTERVAL_KEY = "auto_fetch_interval_minutes"

# Severity weights for logical (non-alphabetical) priority sorting.
# URGENT and CRITICAL are treated as the same top severity.
_PRIORITY_WEIGHTS: dict[str, int] = {
    "CRITICAL": 4,
    "URGENT": 4,
    "HIGH": 3,
    "MEDIUM": 2,
    "LOW": 1,
    "IGNORED": 0,
}

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
QTextBrowser {
    background-color: #181825;
    color: #cdd6f4;
    border: 1px solid #313244;
    border-radius: 6px;
    padding: 8px;
    font-family: 'Segoe UI', 'Roboto', sans-serif;
    font-size: 13px;
}
QStatusBar {
    background-color: #11111b;
    color: #a6adc8;
}
QMenuBar {
    background-color: #11111b;
    color: #cdd6f4;
}
QMenuBar::item:selected {
    background-color: #313244;
}
QMenu {
    background-color: #181825;
    color: #cdd6f4;
    border: 1px solid #313244;
}
QMenu::item:selected {
    background-color: #45475a;
}
QDialog {
    background-color: #1e1e2e;
    color: #cdd6f4;
}
QSpinBox {
    background-color: #181825;
    color: #cdd6f4;
    border: 1px solid #313244;
    border-radius: 4px;
    padding: 4px;
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
    "IGNORED": "#6c7086",
}


# ---------------------------------------------------------------------------
# Custom table item for logical priority sorting
# ---------------------------------------------------------------------------

class PriorityTableItem(QTableWidgetItem):
    """A table item that sorts by priority *severity* rather than text.

    Sorting falls back to alphabetical order only when two priorities share
    the same weight (which normally never happens).
    """

    def __lt__(self, other: QTableWidgetItem) -> bool:
        if isinstance(other, PriorityTableItem):
            self_weight = _PRIORITY_WEIGHTS.get(self.text().upper(), -1)
            other_weight = _PRIORITY_WEIGHTS.get(other.text().upper(), -1)
            if self_weight != other_weight:
                return self_weight < other_weight
        return super().__lt__(other)


class DateTableItem(QTableWidgetItem):
    """A table item that stores the original ISO date string but displays it nicely, and sorts logically."""
    
    def __init__(self, iso_date_str: str) -> None:
        super().__init__()
        self._iso_date = iso_date_str
        try:
            dt = datetime.fromisoformat(iso_date_str)
            self.setText(dt.strftime("%b %d, %Y %I:%M %p"))
        except (ValueError, TypeError):
            self.setText(iso_date_str)

    def __lt__(self, other: QTableWidgetItem) -> bool:
        if isinstance(other, DateTableItem):
            return self._iso_date < other._iso_date
        return super().__lt__(other)

def _serialize_result(result: dict[str, Any]) -> dict[str, Any]:
    """Convert a LangGraph result (with Pydantic models) into a plain dict.

    The returned structure is JSON-serialisable and is what gets stored in
    the ``extracted_json`` column and rendered in the detail panel.
    """
    payload: dict[str, Any] = {}
    for key in ("routing_data", "city_notice", "maintenance", "dispute"):
        value = result.get(key)
        payload[key] = value.model_dump() if value is not None else None
    error = result.get("error")
    if error:
        payload["error"] = str(error)
    return payload


# ---------------------------------------------------------------------------
# Worker threads
# ---------------------------------------------------------------------------

class FetchEmailsWorker(QThread):
    """Fetch unread emails from Gmail in a background thread."""

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
        except FileNotFoundError as exc:
            logger.error("Missing credentials: %s", exc)
            self.error.emit(
                "credentials.json not found. Download your OAuth2 client "
                "credentials from the Google Cloud Console and place the "
                "file in the application directory."
            )
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


class SaveTicketWorker(QThread):
    """Persist a single ticket to SQLite, then return all tickets.

    Keeping the write (and the follow-up read) on a background thread ensures
    the UI never blocks on database I/O.
    """

    finished = pyqtSignal(list)
    error = pyqtSignal(str)

    def __init__(
        self,
        db: Database,
        ticket: dict[str, Any],
        parent: QThread | None = None,
    ) -> None:
        super().__init__(parent)
        self._db = db
        self._ticket = ticket

    def run(self) -> None:
        try:
            self._db.insert_ticket(**self._ticket)
            self.finished.emit(self._db.get_all_tickets())
        except Exception as exc:
            logger.exception("Ticket save failed")
            self.error.emit(str(exc))


# ---------------------------------------------------------------------------
# Settings dialog
# ---------------------------------------------------------------------------

class SettingsDialog(QDialog):
    """Lets the user configure the auto-fetch interval (in minutes)."""

    def __init__(self, current_interval: int, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.setMinimumWidth(360)

        layout = QVBoxLayout(self)

        form = QFormLayout()
        self._interval_spin = QSpinBox()
        self._interval_spin.setRange(0, 1440)
        self._interval_spin.setValue(current_interval)
        self._interval_spin.setSuffix(" min")
        self._interval_spin.setToolTip("Set to 0 to disable automatic fetching.")
        form.addRow("Auto-Fetch Interval:", self._interval_spin)
        layout.addLayout(form)

        hint = QLabel("Set to 0 to disable automatic fetching.")
        hint.setStyleSheet("color:#a6adc8; font-size:11px;")
        layout.addWidget(hint)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok
            | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def interval_minutes(self) -> int:
        return self._interval_spin.value()


# ---------------------------------------------------------------------------
# Main window
# ---------------------------------------------------------------------------

class InboxSupervisorWindow(QMainWindow):
    """Property Management Inbox Supervisor dashboard."""

    def __init__(self) -> None:
        super().__init__()
        self.setWindowTitle("Property Manager — Inbox Supervisor")
        self.setMinimumSize(1200, 700)

        self._pending_emails: list[dict[str, str]] = []
        self._current_worker: ProcessEmailWorker | None = None
        self._save_worker: SaveTicketWorker | None = None
        self._processed_count = 0
        self._batch_total = 0
        self._is_fetching = False

        # Persistence + preferences.
        self._db = Database()
        self._settings = QSettings(_SETTINGS_ORG, _SETTINGS_APP)

        self._build_ui()
        self.setStyleSheet(DARK_STYLE)

        # Auto-fetch timer (started from the persisted interval).
        self._auto_fetch_timer = QTimer(self)
        self._auto_fetch_timer.timeout.connect(self._on_auto_fetch)
        self._apply_auto_fetch_interval()

        # Populate the table strictly from the database on startup.
        self._refresh_table_from_db(self._db.get_all_tickets())

    # ---- UI construction ---------------------------------------------------

    def _build_ui(self) -> None:
        self._build_menu_bar()

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

        self._export_btn = QPushButton("Export to CSV")
        self._export_btn.clicked.connect(self._on_export_clicked)
        title_bar.addWidget(self._export_btn)

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
        self._table.setColumnCount(5)
        self._table.setHorizontalHeaderLabels(
            ["Received Date", "Subject", "Classification", "Priority", "Property Address"]
        )
        self._table.setAlternatingRowColors(True)
        self._table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self._table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self._table.setSortingEnabled(True)
        header = self._table.horizontalHeader()
        if header is not None:
            header.setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch)
            header.setSectionResizeMode(2, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(3, QHeaderView.ResizeMode.ResizeToContents)
            header.setSectionResizeMode(4, QHeaderView.ResizeMode.Stretch)
        self._table.currentCellChanged.connect(self._on_row_selected)
        splitter.addWidget(self._table)

        # Right: detail panel
        detail_widget = QWidget()
        detail_layout = QVBoxLayout(detail_widget)
        detail_layout.setContentsMargins(8, 0, 0, 0)
        detail_label = QLabel("Extracted Details")
        detail_label.setFont(QFont("Segoe UI", 14, QFont.Weight.Bold))
        detail_layout.addWidget(detail_label)
        self._detail_view = QTextBrowser()
        self._detail_view.setReadOnly(True)
        self._detail_view.setOpenExternalLinks(False)
        detail_layout.addWidget(self._detail_view)
        splitter.addWidget(detail_widget)

        splitter.setSizes([700, 500])
        root_layout.addWidget(splitter)

        # Status bar
        self._status_bar = QStatusBar()
        self.setStatusBar(self._status_bar)
        self._status_bar.showMessage("Ready — click 'Fetch New Emails' to start.")

    def _build_menu_bar(self) -> None:
        menu_bar = self.menuBar()
        if menu_bar is None:
            return
        # Push the menu to the global macOS menu bar at the top of the screen
        # (no-op on Windows/Linux, where the menu stays in the window).
        menu_bar.setNativeMenuBar(True)
        settings_menu = menu_bar.addMenu("Settings")
        if settings_menu is None:
            return
        settings_action = settings_menu.addAction("Auto-Fetch Interval...")
        if settings_action is not None:
            settings_action.triggered.connect(self._open_settings_dialog)

    # ---- Settings / auto-fetch --------------------------------------------

    def _stored_interval(self) -> int:
        """Read the persisted auto-fetch interval (minutes) from QSettings."""
        value = self._settings.value(_SETTINGS_INTERVAL_KEY, 0)
        try:
            return int(value)
        except (TypeError, ValueError):
            return 0

    def _open_settings_dialog(self) -> None:
        dialog = SettingsDialog(self._stored_interval(), self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            minutes = dialog.interval_minutes()
            self._settings.setValue(_SETTINGS_INTERVAL_KEY, minutes)
            self._apply_auto_fetch_interval()

    def _apply_auto_fetch_interval(self) -> None:
        """(Re)start or stop the auto-fetch timer from the stored interval."""
        minutes = self._stored_interval()
        if minutes > 0:
            self._auto_fetch_timer.start(minutes * 60 * 1000)
            self._status_bar.showMessage(
                f"Auto-fetch enabled — every {minutes} minute(s)."
            )
        else:
            self._auto_fetch_timer.stop()

    def _on_auto_fetch(self) -> None:
        """Timer-driven fetch; skips if a fetch is already running."""
        if self._is_fetching:
            return
        logger.info("Auto-fetch triggered")
        self._on_fetch_clicked()

    # ---- Slots -------------------------------------------------------------

    def _on_fetch_clicked(self) -> None:
        if self._is_fetching:
            return
        self._is_fetching = True
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
            self._end_fetch_cycle()
            return

        self._pending_emails = list(emails)
        self._batch_total = len(emails)
        self._processed_count = 0
        self._progress.setRange(0, self._batch_total)
        self._progress.setValue(0)
        self._status_bar.showMessage(
            f"Processing {self._batch_total} email(s) through AI pipeline..."
        )
        self._process_next_email()

    def _on_fetch_error(self, error_msg: str) -> None:
        self._status_bar.showMessage(f"Fetch error: {error_msg}")
        self._end_fetch_cycle()

    def _end_fetch_cycle(self) -> None:
        """Reset UI state at the end of a fetch/process batch."""
        self._progress.setVisible(False)
        self._fetch_btn.setEnabled(True)
        self._is_fetching = False

    def _process_next_email(self) -> None:
        if not self._pending_emails:
            self._end_fetch_cycle()
            self._status_bar.showMessage(
                f"Done — {self._table.rowCount()} ticket(s) in database."
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
        self._advance_progress()

        routing = result.get("routing_data")
        classification = routing.classification if routing else "UNKNOWN"

        # Task 1: strictly discard IGNORED emails — no DB row, no UI row.
        if classification == "IGNORED":
            logger.info("Discarding IGNORED email: %s", email_data.get("subject"))
            self._process_next_email()
            return

        priority = routing.priority_level if routing else ""
        ticket = {
            "email_id": email_data.get("email_id", ""),
            "date_received": datetime.now().isoformat(timespec="seconds"),
            "sender": email_data.get("sender", ""),
            "subject": email_data.get("subject", "(no subject)"),
            "classification": classification,
            "priority": priority,
            "extracted_json": json.dumps(_serialize_result(result)),
        }

        # Task 2: persist to SQLite on a worker thread, then refresh from DB.
        self._save_worker = SaveTicketWorker(self._db, ticket)
        self._save_worker.finished.connect(self._on_ticket_saved)
        self._save_worker.error.connect(self._on_save_error)
        self._save_worker.start()

    def _on_ticket_saved(self, tickets: list[dict[str, Any]]) -> None:
        self._refresh_table_from_db(tickets)
        self._process_next_email()

    def _on_save_error(self, error_msg: str) -> None:
        self._status_bar.showMessage(f"Database error: {error_msg}")
        self._process_next_email()

    def _on_process_error(self, error_msg: str) -> None:
        self._status_bar.showMessage(f"Processing error: {error_msg}")
        self._advance_progress()
        self._process_next_email()

    def _advance_progress(self) -> None:
        self._processed_count += 1
        self._progress.setValue(self._processed_count)
        self._status_bar.showMessage(
            f"Processed {self._processed_count} / {self._batch_total} email(s)..."
        )

    # ---- Table population (from the database) ------------------------------

    def _refresh_table_from_db(self, tickets: list[dict[str, Any]]) -> None:
        """Rebuild the table strictly from database rows.

        Sorting is disabled during insertion (so row indices stay stable while
        populating) and re-enabled afterwards. Each row stashes its parsed
        ``extracted_json`` on the column-0 item so the detail panel survives
        re-sorting.
        """
        self._table.setSortingEnabled(False)
        self._table.setRowCount(0)

        for ticket in tickets:
            payload = self._parse_payload(ticket.get("extracted_json"))
            routing = payload.get("routing_data") or {}
            classification = ticket.get("classification", "")
            priority = ticket.get("priority", "")
            address = routing.get("property_address", "") or "—"
            date_received = ticket.get("date_received", "")

            row = self._table.rowCount()
            self._table.insertRow(row)
            
            date_item = DateTableItem(date_received)
            date_item.setData(Qt.ItemDataRole.UserRole, json.dumps(payload))
            self._table.setItem(row, 0, date_item)

            subject_item = QTableWidgetItem(ticket.get("subject", "(no subject)"))
            self._table.setItem(row, 1, subject_item)

            cls_item = QTableWidgetItem(classification)
            cls_item.setForeground(
                QColor(_CLASSIFICATION_COLOURS.get(classification, "#cdd6f4"))
            )
            self._table.setItem(row, 2, cls_item)

            pri_item = PriorityTableItem(priority)
            pri_item.setForeground(
                QColor(_PRIORITY_COLOURS.get(priority.upper(), "#cdd6f4"))
            )
            self._table.setItem(row, 3, pri_item)

            self._table.setItem(row, 4, QTableWidgetItem(address))

        self._table.setSortingEnabled(True)

    @staticmethod
    def _parse_payload(raw: Any) -> dict[str, Any]:
        if not raw:
            return {}
        try:
            data = json.loads(raw)
            return data if isinstance(data, dict) else {}
        except (TypeError, ValueError):
            return {}

    def _on_row_selected(self, row: int, _col: int, _prev_row: int, _prev_col: int) -> None:
        if row < 0:
            return
        item = self._table.item(row, 0)
        if item is None:
            return
        payload = self._parse_payload(item.data(Qt.ItemDataRole.UserRole))
        self._detail_view.setHtml(self._format_result(payload))

    # ---- CSV export --------------------------------------------------------

    def _on_export_clicked(self) -> None:
        tickets = self._db.get_all_tickets()
        if not tickets:
            QMessageBox.information(
                self, "Export to CSV", "There are no tickets to export yet."
            )
            return

        path, _ = QFileDialog.getSaveFileName(
            self, "Export Tickets to CSV", "tickets.csv", "CSV Files (*.csv)"
        )
        if not path:
            return

        # Flatten each ticket into {human-readable header: value}. Building the
        # rows first lets us collect the full union of headers (standard columns
        # plus every nested key found in any row).
        rows = [self._flatten_ticket(t) for t in tickets]
        headers: list[str] = []
        seen: set[str] = set()
        for row in rows:
            for header in row:
                if header not in seen:
                    seen.add(header)
                    headers.append(header)

        try:
            with open(path, "w", newline="", encoding="utf-8") as f:
                writer = csv.DictWriter(f, fieldnames=headers)
                writer.writeheader()
                # Missing keys default to "" so e.g. a Maintenance ticket leaves
                # the "Max Potential Fine" cell blank rather than erroring.
                writer.writerows(rows)
        except OSError as exc:
            QMessageBox.critical(self, "Export Failed", f"Could not write file:\n{exc}")
            return

        self._status_bar.showMessage(f"Exported {len(tickets)} ticket(s) to {path}")

    # Standard ticket columns, in export order: (db key, human-readable header).
    _CSV_STANDARD_COLUMNS: tuple[tuple[str, str], ...] = (
        ("date_received", "Date"),
        ("sender", "Sender"),
        ("subject", "Subject"),
        ("classification", "Classification"),
        ("priority", "Priority"),
        ("status", "Status"),
    )

    # Nested keys that should never be exported (backend fields + duplicates of
    # the standard columns above).
    _CSV_SKIP_KEYS: frozenset[str] = frozenset(
        {"email_id", "sender_type", "original_email_text", "classification", "priority_level"}
    )

    def _flatten_ticket(self, ticket: dict[str, Any]) -> dict[str, str]:
        """Flatten one DB row into {human-readable header: value}.

        Standard columns come first, followed by every key parsed out of the
        ``extracted_json`` payload. Empty / malformed JSON is handled gracefully.
        """
        flat: dict[str, str] = {}
        for db_key, header in self._CSV_STANDARD_COLUMNS:
            flat[header] = self._csv_value(ticket.get(db_key, ""))

        payload = self._parse_payload(ticket.get("extracted_json"))
        for section in ("routing_data", "city_notice", "maintenance", "dispute"):
            data = payload.get(section)
            if not isinstance(data, dict):
                continue
            for key, value in data.items():
                if key in self._CSV_SKIP_KEYS:
                    continue
                flat[self._humanize(key)] = self._csv_value(value)
        return flat

    @staticmethod
    def _csv_value(value: Any) -> str:
        """Render a value for a CSV cell (bools as Yes/No, blanks for empty)."""
        if value is None:
            return ""
        if isinstance(value, bool):
            return "Yes" if value else "No"
        return str(value).strip()

    # ---- Helpers -----------------------------------------------------------

    @staticmethod
    def _humanize(field_name: str) -> str:
        """Convert a snake_case field name into a Title Case label."""
        return field_name.replace("_", " ").title()

    @staticmethod
    def _format_value(value: Any) -> str:
        """Render a field value as display-ready text."""
        if isinstance(value, bool):
            return "Yes" if value else "No"
        text = str(value).strip()
        return text if text else "—"

    def _render_section(self, title: str, data: dict[str, Any]) -> str:
        """Render a titled block of key-value rows as HTML."""
        rows = "".join(
            f"<tr>"
            f"<td style='padding:4px 12px 4px 0; color:#a6adc8; "
            f"vertical-align:top; white-space:nowrap;'>{self._humanize(key)}</td>"
            f"<td style='padding:4px 0; color:#cdd6f4;'>{self._format_value(val)}</td>"
            f"</tr>"
            for key, val in data.items()
        )
        return (
            f"<h3 style='color:#89b4fa; margin-bottom:4px;'>{title}</h3>"
            f"<table style='border-collapse:collapse; width:100%;'>{rows}</table>"
        )

    # Backend / system fields that must never be shown to the user.
    _HIDDEN_FIELDS: frozenset[str] = frozenset(
        {"email_id", "sender_type", "original_email_text"}
    )

    def _format_result(self, result: dict[str, Any]) -> str:
        """Format a serialized result as human-readable HTML for the detail panel.

        ``result`` is the plain-dict payload produced by :func:`_serialize_result`
        (i.e. what is stored in the ``extracted_json`` column).
        """
        routing = result.get("routing_data")

        if not routing:
            error = result.get("error")
            if error:
                return (
                    "<p style='color:#f38ba8;'>An error occurred while processing "
                    f"this email:</p><p style='color:#a6adc8;'>{error}</p>"
                )
            return "<p style='color:#a6adc8;'>No details available.</p>"

        # Ignored emails get a clean, friendly message with no extracted fields.
        if routing.get("classification") == "IGNORED":
            return (
                "<p style='color:#a6adc8; font-size:14px; margin-top:8px;'>"
                "This email was classified as unrelated to property management "
                "and was ignored.</p>"
            )

        sections: list[str] = []

        # Overview: classification + priority + address (hide backend fields).
        overview = {
            key: val
            for key, val in routing.items()
            if key not in self._HIDDEN_FIELDS
        }
        sections.append(self._render_section("Overview", overview))

        # Specialist extraction details.
        specialist_titles = {
            "city_notice": "City / Legal Notice",
            "maintenance": "Maintenance Request",
            "dispute": "Tenant Dispute",
        }
        for key, title in specialist_titles.items():
            value = result.get(key)
            if value:
                data = {
                    k: v
                    for k, v in value.items()
                    if k not in self._HIDDEN_FIELDS
                }
                sections.append(self._render_section(title, data))

        error = result.get("error")
        if error:
            sections.append(
                f"<h3 style='color:#f38ba8; margin-bottom:4px;'>Error</h3>"
                f"<p style='color:#a6adc8;'>{error}</p>"
            )

        return "".join(sections)