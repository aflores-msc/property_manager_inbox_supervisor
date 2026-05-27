# Property Manager — Inbox Supervisor

A native desktop application that connects to Gmail via OAuth2, fetches unread emails, and automatically classifies them through a multi-agent LangGraph pipeline backed by Google Gemini. Processed emails are displayed on a dark-mode monitoring dashboard built with PyQt6.

## Features

- **Gmail API with OAuth2** — secure Desktop Flow authentication using `credentials.json`
- **AI-powered classification** — routes emails to MAINTENANCE, CITY_NOTICE, or TENANT_DISPUTE
- **Multi-agent pipeline** — specialist agents extract structured data per category
- **Real-time dashboard** — PyQt6 dark-mode UI with progress tracking and JSON detail view
- **Structured output** — uses Gemini's `response_schema` for guaranteed JSON responses

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌───────────────────────────────────┐
│  Gmail API  │────▶│ EmailFetcher │────▶│         LangGraph Pipeline        │
│  (OAuth2)   │     │              │     │                                   │
└─────────────┘     └──────────────┘     │  Supervisor ──▶ Conditional Edge  │
                                         │       │                           │
                                         │  ┌────┴────┬──────────┐          │
                                         │  ▼         ▼          ▼          │
                                         │ Legal   Maintenance  Dispute     │
                                         │ Agent     Agent       Agent      │
                                         └───────────────────────────────────┘
                                                        │
                                                        ▼
                                              ┌──────────────────┐
                                              │  PyQt6 Dashboard  │
                                              └──────────────────┘
```

## Quick Start

### 1. Install dependencies

```bash
pip install -r requirements.txt
```

### 2. Set up Gmail API credentials

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a project and enable the **Gmail API**
3. Create **OAuth 2.0 Client ID** credentials (Desktop application type)
4. Download the JSON file and save it as `credentials.json` in the application directory

### 3. Configure environment

```bash
cp .env.example .env
# Edit .env with your Gemini API key and email address
```

### 4. Run the application

```bash
python main.py
```

On first run, a browser window will open for Gmail OAuth2 authorization. After granting access, a `token.json` file will be saved for subsequent runs.

### 5. Build standalone executable (Windows)

```batch
build.bat
```

> **Note:** Place `credentials.json` in the same directory as the built executable. The `token.json` will also be created there after the first OAuth2 login.

## Project Structure

```
├── main.py              # Application entry point
├── src/
│   ├── config.py        # Pydantic Settings configuration
│   ├── models.py        # Pydantic schemas & LangGraph state
│   ├── email_client.py  # Gmail API OAuth2 connection and fetching
│   ├── agents.py        # LangGraph nodes and graph compilation
│   └── ui.py            # PyQt6 dashboard and QThread workers
├── credentials.json     # OAuth2 client credentials (not committed)
├── token.json           # OAuth2 refresh token (auto-generated, not committed)
├── .env.example         # Environment variable template
├── requirements.txt     # Python dependencies
└── build.bat            # PyInstaller build script
```

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
