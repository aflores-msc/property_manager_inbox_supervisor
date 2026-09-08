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
        """Create the ``tickets`` table on first run (idempotent).

        Also runs a lightweight migration that adds the ``raw_body`` column
        to pre-existing databases via ``ALTER TABLE`` so users do not have to
        delete their ``property_manager.db`` to pick up the new schema.
        """
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
                    raw_body TEXT,
                    status TEXT DEFAULT 'OPEN'
                )
                """
            )
            conn.commit()
            self._migrate_raw_body(conn)
        logger.info("Database initialized at %s", self._db_path)

    def _migrate_raw_body(self, conn: sqlite3.Connection) -> None:
        """Add the ``raw_body`` column to legacy databases if missing."""
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(tickets)")}
        if "raw_body" not in columns:
            conn.execute("ALTER TABLE tickets ADD COLUMN raw_body TEXT")
            conn.commit()
            print(
                "[Estate Beacon] Database upgraded: added 'raw_body' column. "
                "Existing tickets will show no original email body until they are "
                "re-fetched. If you prefer a clean slate, delete "
                f"{self._db_path} and it will be recreated with the new schema."
            )

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
        raw_body: str = "",
        status: str = "OPEN",
    ) -> bool:
        """Insert a ticket, ignoring duplicates by ``email_id``.

        Uses ``INSERT OR IGNORE`` so re-fetching an already-stored email is
        a safe no-op rather than raising a UNIQUE constraint error. The raw,
        plain-text email body is persisted in ``raw_body`` for later auditing.

        Returns ``True`` if a new row was actually inserted, ``False`` if the
        email was already present (and therefore ignored).
        """
        with self._connect() as conn:
            cursor = conn.execute(
                """
                INSERT OR IGNORE INTO tickets (
                    email_id, date_received, sender, subject,
                    classification, priority, extracted_json, raw_body, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    email_id,
                    date_received,
                    sender,
                    subject,
                    classification,
                    priority,
                    extracted_json,
                    raw_body,
                    status,
                ),
            )
            conn.commit()
            return cursor.rowcount > 0

    def get_all_tickets(self) -> list[dict[str, Any]]:
        """Return every ticket as a list of dicts, newest first."""
        with self._connect() as conn:
            rows = conn.execute(
                """
                SELECT id, email_id, date_received, sender, subject,
                       classification, priority, extracted_json, raw_body, status
                FROM tickets
                ORDER BY id DESC
                """
            ).fetchall()
        return [dict(row) for row in rows]

    def get_raw_body(self, email_id: str) -> str:
        """Return the stored raw email body for ``email_id`` (empty if none)."""
        with self._connect() as conn:
            row = conn.execute(
                "SELECT raw_body FROM tickets WHERE email_id = ?",
                (email_id,),
            ).fetchone()
        if row is None:
            return ""
        return row["raw_body"] or ""
