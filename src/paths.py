"""Cross-platform application data directory.

All user data — ``.env``, ``credentials.json``, ``token.json`` and the
SQLite database — lives in a single hidden folder under the user's home
directory (``~/.property_manager_ai``). Using :class:`pathlib.Path` keeps
this correct on Windows, macOS, and Linux, and — crucially — survives being
packaged into a macOS ``.app`` bundle where ``os.getcwd()`` is unreliable.
"""

from __future__ import annotations

from pathlib import Path

APP_DIR_NAME = ".property_manager_ai"


def get_app_dir() -> Path:
    """Return the application data directory, creating it if necessary.

    The directory is ``~/.property_manager_ai``. It is created on first
    access (including any missing parents) and is safe to call repeatedly.
    """
    app_dir = Path.home() / APP_DIR_NAME
    app_dir.mkdir(parents=True, exist_ok=True)
    return app_dir


def get_app_file(filename: str) -> Path:
    """Return the full path to ``filename`` inside the application directory."""
    return get_app_dir() / filename
