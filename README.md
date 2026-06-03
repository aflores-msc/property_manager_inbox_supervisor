# Property Manager — Inbox Supervisor

A native desktop application that connects to Gmail via OAuth2, fetches unread emails, and automatically classifies them through a multi-agent LangGraph pipeline backed by Google Gemini. Processed emails are displayed on a dark-mode monitoring dashboard built with PyQt6.

## Features

- **Gmail API with OAuth2** — secure Desktop Flow authentication using `credentials.json`
- **AI-powered classification** — routes emails to MAINTENANCE, CITY_NOTICE, or TENANT_DISPUTE
- **Multi-agent pipeline** — specialist agents extract structured data per category
- **Real-time dashboard** — PyQt6 dark-mode UI with progress tracking and a human-readable detail view
- **Structured output** — uses Gemini's `response_schema` for guaranteed JSON responses
- **SQLite persistence** — processed tickets are stored in a local `property_manager.db`; the dashboard is populated strictly from the database
- **Strict IGNORED filtering** — system alerts, newsletters, and spam are classified as `IGNORED` and discarded entirely (never saved, never shown)
- **Auto-fetch timer** — configure an interval (in minutes) via **Settings**; persisted across launches with `QSettings`
- **Export to CSV** — export all stored tickets to a CSV file
- **Logical priority sorting** — the Priority column sorts by severity (URGENT/CRITICAL > HIGH > MEDIUM > LOW) rather than alphabetically

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌───────────────────────────────────┐
│  Gmail API  │────▶│ EmailFetcher │────▶│         LangGraph Pipeline        │
│  (OAuth2)   │     │              │     │                                   │
└─────────────┘     └──────────────┘     │  Supervisor ──▶ Conditional Edge  │
                                         │       │                           │
                                         │  ┌────┴────┬──────────┐           │
                                         │  ▼         ▼          ▼           │
                                         │ Legal   Maintenance  Dispute      │
                                         │ Agent     Agent       Agent       │
                                         └───────────────────────────────────┘
                                                        │
                                                        ▼
                                              ┌──────────────────┐
                                              │  PyQt6 Dashboard │
                                              └──────────────────┘
```

## Quick Start

### 1. Install dependencies

```bash
pip install -r requirements.txt
```

### 2. Application data directory

All user data lives in a single hidden folder in your home directory, created automatically on first launch:

```
~/.property_manager_ai/
├── .env               # your configuration (you provide this)
├── credentials.json   # OAuth2 client credentials (you provide this)
├── token.json         # OAuth2 refresh token (auto-generated)
└── property_manager.db # SQLite database (auto-generated)
```

This works identically on macOS, Windows, and Linux — and survives being packaged into a macOS `.app` bundle.

### 3. Set up Gmail API credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project and enable the **Gmail API**
3. Create **OAuth 2.0 Client ID** credentials (Desktop application type)
4. Download the JSON file and save it as `credentials.json` in `~/.property_manager_ai/`

### 4. Configure environment

```bash
cp .env.example ~/.property_manager_ai/.env
# Edit ~/.property_manager_ai/.env with your Gemini API key and email address
```

### 5. Run the application

```bash
python main.py
```

On first run, a browser window will open for Gmail OAuth2 authorization. After granting access, a `token.json` file will be saved for subsequent runs.

### 6. Build a standalone bundle

**Windows:**

```batch
build.bat
```

**macOS** (produces `dist/Inbox Supervisor.app`):

```bash
./build_mac.sh
```

> **Note:** The bundle reads its data from `~/.property_manager_ai/`, so place your `.env` and `credentials.json` there before first launch.

## Project Structure

```
├── main.py              # Application entry point
├── src/
│   ├── config.py        # Pydantic Settings configuration
│   ├── models.py        # Pydantic schemas & LangGraph state
│   ├── email_client.py  # Gmail API OAuth2 connection and fetching
│   ├── agents.py        # LangGraph nodes and graph compilation
│   ├── database.py      # SQLite persistence (tickets table + DAO methods)
│   ├── paths.py         # Cross-platform application data directory helper
│   └── ui.py            # PyQt6 dashboard and QThread workers
├── .env.example         # Environment variable template
├── requirements.txt     # Python dependencies
├── build.bat            # PyInstaller build script (Windows)
└── build_mac.sh         # PyInstaller build script (macOS .app bundle)
```

> User data (`.env`, `credentials.json`, `token.json`, `property_manager.db`) lives in `~/.property_manager_ai/`, not in the project directory.

## Environment Variables

| Variable | Description |
|---|---|
| `GOOGLE_API_KEY` | Google Gemini API key |
| `GEMINI_MODEL_NAME` | Model name (default: `gemini-2.5-flash`) |
| `MANAGER_EMAIL` | Property manager's email address |
| `EMAIL_USERNAME` | Gmail account (display / reference only) |

## Tech Stack

- **Python 3.13** with strict type hinting
- **PyQt6** — modern desktop UI framework
- **LangGraph** — multi-agent orchestration
- **google-genai** — Gemini LLM integration
- **Gmail API** — email fetching via OAuth2 Desktop Flow
- **pydantic-settings** — configuration management
