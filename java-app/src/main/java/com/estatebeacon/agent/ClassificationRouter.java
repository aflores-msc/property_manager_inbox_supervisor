package com.estatebeacon.agent;

import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;

/** Conditional edge: routes to the appropriate specialist node. Mirrors {@code _route_by_classification}. */
public class ClassificationRouter implements EdgeAction<PropertyManagementState> {

    public static final String LEGAL_AGENT = "legal_agent";
    public static final String MAINTENANCE_AGENT = "maintenance_agent";
    public static final String DISPUTE_AGENT = "dispute_agent";

    @Override
    public String apply(PropertyManagementState state) {
        return state.routingData()
                .map(routing -> switch (routing.classification()) {
                    case CITY_NOTICE -> LEGAL_AGENT;
                    case MAINTENANCE -> MAINTENANCE_AGENT;
                    case TENANT_DISPUTE -> DISPUTE_AGENT;
                    case IGNORED -> StateGraph.END;
                })
                .orElse(StateGraph.END);
    }
}
