# Property Manager — Inbox Supervisor

A native desktop application that connects to an IMAP email server, fetches unread emails, and automatically classifies them through a multi-agent LangGraph pipeline backed by Google Gemini. Processed emails are displayed on a dark-mode monitoring dashboard built with PyQt6.

## Features

- **Automated IMAP email fetching** — securely connects to any IMAP server
- **AI-powered classification** — routes emails to MAINTENANCE, CITY_NOTICE, or TENANT_DISPUTE
- **Multi-agent pipeline** — specialist agents extract structured data per category
- **Real-time dashboard** — PyQt6 dark-mode UI with progress tracking and JSON detail view
- **Structured output** — uses Gemini's `response_schema` for guaranteed JSON responses

## Architecture

```
┌─────────────┐     ┌──────────────┐     ┌───────────────────────────────────┐
│  IMAP Server│────▶│ EmailFetcher │────▶│         LangGraph Pipeline        │
└─────────────┘     └──────────────┘     │                                   │
                                         │  Supervisor ──▶ Conditional Edge  │
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

### 2. Configure environment

```bash
cp .env.example .env
# Edit .env with your actual credentials
```

### 3. Run the application

```bash
python main.py
```

### 4. Build standalone executable (Windows)

```batch
build.bat
```

## Project Structure

```
├── main.py              # Application entry point
├── src/
│   ├── config.py        # Pydantic Settings configuration
│   ├── models.py        # Pydantic schemas & LangGraph state
│   ├── email_client.py  # IMAP connection and fetching
│   ├── agents.py        # LangGraph nodes and graph compilation
│   └── ui.py            # PyQt6 dashboard and QThread workers
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
| `EMAIL_IMAP_SERVER` | IMAP server hostname |
| `EMAIL_PORT_IMAP` | IMAP port (default: `993`) |
| `EMAIL_USERNAME` | Email account username |
| `EMAIL_PASSWORD` | Email account password |

## Tech Stack

- **Python 3.13** with strict type hinting
- **PyQt6** — modern desktop UI framework
- **LangGraph** — multi-agent orchestration
- **google-genai** — Gemini LLM integration
- **pydantic-settings** — configuration management
- **imaplib / email** — standard library IMAP client
