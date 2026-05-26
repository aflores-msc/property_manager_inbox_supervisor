"""IMAP email fetching logic."""

from __future__ import annotations

import email
import imaplib
import uuid
from email.header import decode_header
from typing import Any

from src.config import config


def _decode_header_value(raw: Any) -> str:
    """Decode an email header that may contain encoded words."""
    if raw is None:
        return ""
    decoded_parts: list[str] = []
    for part, charset in decode_header(str(raw)):
        if isinstance(part, bytes):
            decoded_parts.append(part.decode(charset or "utf-8", errors="replace"))
        else:
            decoded_parts.append(part)
    return " ".join(decoded_parts)


def _extract_plain_text(msg: email.message.Message) -> str:
    """Walk a MIME message and return the first text/plain payload."""
    if msg.is_multipart():
        for part in msg.walk():
            content_type = part.get_content_type()
            disposition = str(part.get("Content-Disposition", ""))
            if content_type == "text/plain" and "attachment" not in disposition:
                payload = part.get_payload(decode=True)
                if payload is not None:
                    charset = part.get_content_charset() or "utf-8"
                    return payload.decode(charset, errors="replace")
    else:
        payload = msg.get_payload(decode=True)
        if payload is not None:
            charset = msg.get_content_charset() or "utf-8"
            return payload.decode(charset, errors="replace")
    return ""


class EmailFetcher:
    """Connects to an IMAP server and fetches unread emails."""

    def __init__(self) -> None:
        self._server: str = config.EMAIL_IMAP_SERVER
        self._port: int = config.EMAIL_PORT_IMAP
        self._username: str = config.EMAIL_USERNAME
        self._password: str = config.EMAIL_PASSWORD
        self._connection: imaplib.IMAP4_SSL | None = None

    def connect(self) -> None:
        """Establish an SSL connection and authenticate."""
        self._connection = imaplib.IMAP4_SSL(self._server, self._port)
        self._connection.login(self._username, self._password)

    def disconnect(self) -> None:
        """Close the mailbox and log out."""
        if self._connection is not None:
            try:
                self._connection.close()
            except imaplib.IMAP4.error:
                pass
            try:
                self._connection.logout()
            except imaplib.IMAP4.error:
                pass
            self._connection = None

    def fetch_unread_emails(self) -> list[dict[str, str]]:
        """Retrieve all unread emails from the INBOX.

        Returns a list of dicts with keys:
            email_id, subject, sender, body
        """
        if self._connection is None:
            self.connect()
        assert self._connection is not None

        self._connection.select("INBOX")
        status, message_ids = self._connection.search(None, "UNSEEN")
        if status != "OK" or not message_ids or not message_ids[0]:
            return []

        emails: list[dict[str, str]] = []
        for mid in message_ids[0].split():
            status, msg_data = self._connection.fetch(mid, "(RFC822)")
            if status != "OK" or not msg_data:
                continue
            for response_part in msg_data:
                if not isinstance(response_part, tuple):
                    continue
                msg = email.message_from_bytes(response_part[1])
                subject = _decode_header_value(msg["Subject"])
                sender = _decode_header_value(msg["From"])
                body = _extract_plain_text(msg)
                emails.append(
                    {
                        "email_id": uuid.uuid4().hex[:12],
                        "subject": subject,
                        "sender": sender,
                        "body": body,
                    }
                )
        return emails
