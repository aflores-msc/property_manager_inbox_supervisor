#!/usr/bin/env bash
#
# Build a native macOS .app bundle for the Property Manager Inbox Supervisor.
#
# Produces "dist/Inbox Supervisor.app". The --windowed flag makes it a GUI
# app bundle so no Terminal window opens in the background.
#
# User data (.env, credentials.json, token.json, property_manager.db) is read
# from ~/.property_manager_ai — it is intentionally NOT bundled into the .app.

set -euo pipefail

APP_NAME="Inbox Supervisor"
ICON_FILE="icon.icns"

echo "Building ${APP_NAME}.app..."

# Ensure PyInstaller is available.
if ! command -v pyinstaller >/dev/null 2>&1; then
    echo "PyInstaller not found. Install it with: pip install pyinstaller"
    exit 1
fi

# Clean previous build artifacts for a reproducible bundle.
rm -rf build "dist/${APP_NAME}.app" "${APP_NAME}.spec"

pyinstaller \
    --windowed \
    --name "${APP_NAME}" \
    --icon="${ICON_FILE}" \
    --noconfirm \
    main.py

echo "Build complete! Bundle is at: dist/${APP_NAME}.app"
echo "Before first launch, place your .env and credentials.json in: ~/.property_manager_ai"
