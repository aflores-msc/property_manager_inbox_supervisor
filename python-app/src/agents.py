"""LangGraph multi-agent routing with Google Gemini structured output."""

from __future__ import annotations

import json
import logging
from typing import Any

from google import genai
from google.genai import types
from langgraph.graph import END, StateGraph

from src.config import config
from src.models import (
    CityNoticeSchema,
    DisputeSchema,
    MaintenanceSchema,
    PropertyManagementState,
    UniversalRoutingData,
)

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Gemini client
# ---------------------------------------------------------------------------

_client = genai.Client(api_key=config.GOOGLE_API_KEY)


def _call_gemini(prompt: str, response_schema: type) -> dict[str, Any]:
    """Send a prompt to Gemini and return parsed structured JSON."""
    response = _client.models.generate_content(
        model=config.GEMINI_MODEL_NAME,
        contents=prompt,
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=response_schema,
        ),
    )
    return json.loads(response.text)  # type: ignore[arg-type]


# ---------------------------------------------------------------------------
# Graph nodes
# ---------------------------------------------------------------------------

def supervisor_node(state: PropertyManagementState) -> dict[str, Any]:
    """Extract universal routing data from the raw email."""
    email_text = state["original_email_text"]
    subject = state.get("original_email_subject", "")
    sender = state.get("original_email_sender", "")

    prompt = (
        "You are a property management email classifier. Analyze the following email "
        "and extract structured routing data.\n\n"
        f"Subject: {subject}\n"
        f"From: {sender}\n\n"
        f"Body:\n{email_text}\n\n"
        "Classify the email as exactly one of: MAINTENANCE, CITY_NOTICE, TENANT_DISPUTE, or IGNORED.\n"
        "If the email is a system alert (e.g., Google sign-in alerts), a newsletter, "
        "marketing spam, or anything unrelated to physical property management, you MUST "
        "classify it as 'IGNORED'.\n"
        "Determine the priority level as one of: LOW, MEDIUM, HIGH, or URGENT.\n"
        "Extract the property address and unit number if mentioned.\n"
        "Determine the sender type (e.g. 'tenant', 'city', 'vendor', 'contractor')."
    )
    try:
        data = _call_gemini(prompt, UniversalRoutingData)
        routing = UniversalRoutingData(**data)
        return {"routing_data": routing}
    except Exception as exc:
        logger.exception("Supervisor node failed")
        return {"error": f"Supervisor extraction failed: {exc}"}


def _route_by_classification(state: PropertyManagementState) -> str:
    """Conditional edge: route to the appropriate specialist node."""
    routing = state.get("routing_data")
    if routing is None:
        return "end"
    classification = routing.classification
    if classification == "IGNORED":
        return "end"
    route_map = {
        "CITY_NOTICE": "legal_agent",
        "MAINTENANCE": "maintenance_agent",
        "TENANT_DISPUTE": "dispute_agent",
    }
    return route_map.get(classification, "end")


def legal_agent(state: PropertyManagementState) -> dict[str, Any]:
    """Extract CityNoticeSchema details from the email."""
    prompt = (
        "You are a legal notice specialist for property management. "
        "Extract structured data from this city/government notice email.\n\n"
        f"Email body:\n{state['original_email_text']}"
    )
    try:
        data = _call_gemini(prompt, CityNoticeSchema)
        return {"city_notice": CityNoticeSchema(**data)}
    except Exception as exc:
        logger.exception("Legal agent failed")
        return {"error": f"Legal agent extraction failed: {exc}"}


def maintenance_agent(state: PropertyManagementState) -> dict[str, Any]:
    """Extract MaintenanceSchema details from the email."""
    prompt = (
        "You are a maintenance request specialist for property management. "
        "Extract structured data from this maintenance request email.\n\n"
        f"Email body:\n{state['original_email_text']}"
    )
    try:
        data = _call_gemini(prompt, MaintenanceSchema)
        return {"maintenance": MaintenanceSchema(**data)}
    except Exception as exc:
        logger.exception("Maintenance agent failed")
        return {"error": f"Maintenance agent extraction failed: {exc}"}


def dispute_agent(state: PropertyManagementState) -> dict[str, Any]:
    """Extract DisputeSchema details from the email."""
    prompt = (
        "You are a tenant dispute specialist for property management. "
        "Extract structured data from this tenant dispute email.\n\n"
        f"Email body:\n{state['original_email_text']}"
    )
    try:
        data = _call_gemini(prompt, DisputeSchema)
        return {"dispute": DisputeSchema(**data)}
    except Exception as exc:
        logger.exception("Dispute agent failed")
        return {"error": f"Dispute agent extraction failed: {exc}"}


# ---------------------------------------------------------------------------
# Compile the graph
# ---------------------------------------------------------------------------

def build_graph() -> StateGraph:
    """Construct and compile the LangGraph property management pipeline."""
    graph = StateGraph(PropertyManagementState)

    graph.add_node("supervisor", supervisor_node)
    graph.add_node("legal_agent", legal_agent)
    graph.add_node("maintenance_agent", maintenance_agent)
    graph.add_node("dispute_agent", dispute_agent)

    graph.set_entry_point("supervisor")

    graph.add_conditional_edges(
        "supervisor",
        _route_by_classification,
        {
            "legal_agent": "legal_agent",
            "maintenance_agent": "maintenance_agent",
            "dispute_agent": "dispute_agent",
            "end": END,
        },
    )

    graph.add_edge("legal_agent", END)
    graph.add_edge("maintenance_agent", END)
    graph.add_edge("dispute_agent", END)

    return graph.compile()
