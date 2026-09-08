"""Pydantic schemas and LangGraph state definitions."""

from __future__ import annotations

from typing import Literal, TypedDict

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Universal routing data extracted by the Supervisor agent
# ---------------------------------------------------------------------------

class UniversalRoutingData(BaseModel):
    """Core routing envelope extracted from every inbound email."""

    email_id: str = Field(description="Unique identifier for the email")
    sender_type: str = Field(description="Type of sender, e.g. 'tenant', 'city', 'vendor'")
    property_address: str = Field(description="Property street address mentioned in email")
    unit_number: str = Field(default="", description="Unit or apartment number if applicable")
    classification: Literal["MAINTENANCE", "CITY_NOTICE", "TENANT_DISPUTE", "IGNORED"] = Field(
        description="Category the email falls into"
    )
    priority_level: Literal["LOW", "MEDIUM", "HIGH", "URGENT"] = Field(
        description="Urgency level of the email"
    )


# ---------------------------------------------------------------------------
# Specialist extraction schemas
# ---------------------------------------------------------------------------

class CityNoticeSchema(BaseModel):
    """Structured data extracted from a city / legal notice email."""

    notice_type: str = Field(description="Type of notice, e.g. 'code violation', 'permit'")
    issuing_authority: str = Field(description="City department or agency that issued the notice")
    deadline: str = Field(default="", description="Compliance deadline if stated (ISO-8601 date)")
    violation_description: str = Field(description="Summary of the violation or notice content")
    required_action: str = Field(description="Action the property manager must take")
    entity_name: str = Field(default="", description="Name of the contact person or official at the issuing authority")
    entity_phone: str = Field(default="", description="Contact phone number for the issuing authority")
    entity_email: str = Field(default="", description="Contact email address for the issuing authority")


class MaintenanceSchema(BaseModel):
    """Structured data extracted from a maintenance request email."""

    issue_type: str = Field(description="Category of maintenance issue, e.g. 'plumbing', 'electrical'")
    location_in_unit: str = Field(description="Where in the unit the issue is located")
    reported_severity: str = Field(description="Tenant-reported severity")
    description: str = Field(description="Full description of the issue")
    tenant_available_times: str = Field(default="", description="When the tenant is available for repairs")


class DisputeSchema(BaseModel):
    """Structured data extracted from a tenant dispute email."""

    dispute_type: str = Field(description="Type of dispute, e.g. 'noise', 'lease terms', 'deposit'")
    parties_involved: str = Field(description="Names or unit numbers of the parties involved")
    tenant_complaint: str = Field(description="Summary of the tenant's complaint")
    desired_resolution: str = Field(description="What the tenant wants as a resolution")
    escalation_needed: bool = Field(description="Whether legal or management escalation is recommended")


# ---------------------------------------------------------------------------
# LangGraph state
# ---------------------------------------------------------------------------

class PropertyManagementState(TypedDict, total=False):
    """Shared state flowing through the LangGraph pipeline."""

    original_email_subject: str
    original_email_sender: str
    original_email_text: str
    routing_data: UniversalRoutingData | None
    city_notice: CityNoticeSchema | None
    maintenance: MaintenanceSchema | None
    dispute: DisputeSchema | None
    error: str | None
