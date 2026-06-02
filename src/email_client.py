"""Gmail API email fetching logic using OAuth2 Desktop Flow."""

from __future__ import annotations

import base64
import logging
import os
from typing import Any

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import InstalledAppFlow
from googleapiclient.discovery import build, Resource

logger = logging.getLogger(__name__)

SCOPES: list[str] = ["https://www.googleapis.com/auth/gmail.readonly"]


def _get_app_dir() -> str:
    """Return the directory where the executable is running.

    Uses ``os.getcwd()`` so that ``credentials.json`` and ``token.json``
    are always read from / written to the working directory of the
    running process — this avoids breakage inside PyInstaller's
    ``_MEIPASS`` temp folder.
    """
    return os.getcwd()


def _credentials_path() -> str:
    return os.path.join(_get_app_dir(), "credentials.json")


def _token_path() -> str:
    return os.path.join(_get_app_dir(), "token.json")


class EmailFetcher:
    """Connects to Gmail via OAuth2 and fetches unread emails."""

    def __init__(self) -> None:
        self._service: Resource | None = None

    def connect(self) -> None:
        """Authenticate via OAuth2 Desktop Flow and build the Gmail service."""
        creds: Credentials | None = None

        token_file = _token_path()
        if os.path.exists(token_file):
            creds = Credentials.from_authorized_user_file(token_file, SCOPES)

        if creds is None or not creds.valid:
            if creds is not None and creds.expired and creds.refresh_token:
                creds.refresh(Request())
            else:
                creds_file = _credentials_path()
                if not os.path.exists(creds_file):
                    raise FileNotFoundError(
                        f"OAuth credentials file not found at '{creds_file}'. "
                        "Download it from the Google Cloud Console and place it "
                        "in the same directory as the application executable."
                    )
                flow = InstalledAppFlow.from_client_secrets_file(creds_file, SCOPES)
                creds = flow.run_local_server(port=0)

            with open(token_file, "w") as f:
                f.write(creds.to_json())

        self._service = build("gmail", "v1", credentials=creds)

    def disconnect(self) -> None:
        """Release the Gmail service handle."""
        self._service = None

    def fetch_unread_emails(self) -> list[dict[str, str]]:
        """Retrieve unread emails from the Gmail inbox.

        Returns a list of dicts with keys:
            email_id, subject, sender, body
        """
        if self._service is None:
            self.connect()
        assert self._service is not None

        results: dict[str, Any] = (
            self._service.users()
            .messages()
            .list(userId="me", labelIds=["UNREAD", "INBOX"], maxResults=50)
            .execute()
        )

        message_refs: list[dict[str, str]] = results.get("messages", [])
        if not message_refs:
            return []

        emails: list[dict[str, str]] = []
        for ref in message_refs:
            msg: dict[str, Any] = (
                self._service.users()
                .messages()
                .get(userId="me", id=ref["id"], format="full")
                .execute()
            )

            headers = {
                h["name"].lower(): h["value"]
                for h in msg.get("payload", {}).get("headers", [])
            }
            subject = headers.get("subject", "(no subject)")
            sender = headers.get("from", "")
            body = _extract_plain_text(msg.get("payload", {}))

            emails.append(
                {
                    "email_id": ref["id"],
                    "subject": subject,
                    "sender": sender,
                    "body": body,
                }
            )

        return emails


def _extract_plain_text(payload: dict[str, Any]) -> str:
    """Recursively walk a Gmail message payload and return plain text."""
    mime_type: str = payload.get("mimeType", "")

    if mime_type == "text/plain":
        data: str = payload.get("body", {}).get("data", "")
        if data:
            return base64.urlsafe_b64decode(data).decode("utf-8", errors="replace")
        return ""

    parts: list[dict[str, Any]] = payload.get("parts", [])
    for part in parts:
        text = _extract_plain_text(part)
        if text:
            return text

    return ""
