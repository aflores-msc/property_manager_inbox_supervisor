"""SQLite persistence layer for processed property-management tickets.

A small DAO around Python's built-in :mod:`sqlite3`. Each method opens its
own short-lived connection so the class is safe to call from multiple
``QThread`` workers without sharing a single connection across threads.
"""

from __future__ import annotations

import logging
import sqlite3
from typing import Any

from src.paths import get_app_file

logger = logging.getLogger(__name__)

DB_FILENAME = "property_manager.db"


def _default_db_path() -> str:
    """Return the database path inside the user-level application directory.

    Stored in ``~/.property_manager_ai`` (via :func:`src.paths.get_app_file`)
    so it is stable across platforms and survives being packaged into a macOS
    ``.app`` bundle, where ``os.getcwd()`` is unreliable.
    """
    return str(get_app_file(DB_FILENAME))


class Database:
    """Data-access object for the ``tickets`` table."""

    def __init__(self, db_path: str | None = None) -> None:
        self._db_path = db_path or _default_db_path()
        self.initialize()

    # ---- connection helpers ------------------------------------------------

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self._db_path)
        conn.row_factory = sqlite3.Row
        return conn

    # ---- schema ------------------------------------------------------------

    def initialize(self) -> None:
        """Create the ``tickets`` table on first run (idempotent)."""
        with self._connect() as conn:
            conn.execute(
                """
                CREATE TABLE IF NOT EXISTS tickets (
                    id INTEGER PRIMARY KEY,
                    email_id TEXT UNIQUE,
                    date_received TEXT,
                    sender TEXT,
                    subject TEXT,
                    classification TEXT,
                    priority TEXT,
                    extracted_json TEXT,
                    status TEXT DEFAULT 'OPEN'
                )
                """
            )
            conn.commit()
        logger.info("Database initialized at %s", self._db_path)

    # ---- DAO methods -------------------------------------------------------

    def insert_ticket(
        self,
        email_id: str,
        date_received: str,
        sender: str,
        subject: str,
        classification: str,
        priority: str,
        extracted_json: str,
        status: str = "OPEN",
    ) -> None:
        """Insert a ticket, ignoring duplicates by ``email_id``.

        Uses ``INSERT OR IGNORE`` so re-fetching an already-stored email is
        a safe no-op rather than raising a UNIQUE constraint error.
        """
        with self._connect() as conn:
            conn.execute(
                """
                INSERT OR IGNORE INTO tickets (
                    email_id, date_received, sender, subject,
                    classification, priority, extracted_json, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    email_id,
                    date_received,
                    sender,
                    subject,
                    classification,
                    priority,
                    extracted_json,
                    status,
                ),
            )
            conn.commit()

    def get_all_tickets(self) -> list[dict[str, Any]]:
        """Return every ticket as a list of dicts, newest first."""
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, email_id, date_received, sender, subject,
                       classification, priority, extracted_json, status
                FROM tickets
                ORDER BY id DESC
                """
            ).fetchall()
        return [dict(row) for row in rows]
