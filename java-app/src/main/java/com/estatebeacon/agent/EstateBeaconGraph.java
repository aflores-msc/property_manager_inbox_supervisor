package com.estatebeacon.agent;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** Constructs and compiles the LangGraph4j property management pipeline. Mirrors {@code agents.py:build_graph}. */
@Component
public class EstateBeaconGraph {

    private final SupervisorNode supervisorNode;
    private final LegalAgentNode legalAgentNode;
    private final MaintenanceAgentNode maintenanceAgentNode;
    private final DisputeAgentNode disputeAgentNode;

    public EstateBeaconGraph(
            SupervisorNode supervisorNode,
            LegalAgentNode legalAgentNode,
            MaintenanceAgentNode maintenanceAgentNode,
            DisputeAgentNode disputeAgentNode
    ) {
        this.supervisorNode = supervisorNode;
        this.legalAgentNode = legalAgentNode;
        this.maintenanceAgentNode = maintenanceAgentNode;
        this.disputeAgentNode = disputeAgentNode;
    }

    public CompiledGraph<PropertyManagementState> compile() throws GraphStateException {
        StateGraph<PropertyManagementState> graph = new StateGraph<>(PropertyManagementState::new)
                .addNode("supervisor", node_async(supervisorNode))
                .addNode(ClassificationRouter.LEGAL_AGENT, node_async(legalAgentNode))
                .addNode(ClassificationRouter.MAINTENANCE_AGENT, node_async(maintenanceAgentNode))
                .addNode(ClassificationRouter.DISPUTE_AGENT, node_async(disputeAgentNode))
                .addEdge(START, "supervisor")
                .addConditionalEdges(
                        "supervisor",
                        edge_async(new ClassificationRouter()),
                        java.util.Map.of(
                                ClassificationRouter.LEGAL_AGENT, ClassificationRouter.LEGAL_AGENT,
                                ClassificationRouter.MAINTENANCE_AGENT, ClassificationRouter.MAINTENANCE_AGENT,
                                ClassificationRouter.DISPUTE_AGENT, ClassificationRouter.DISPUTE_AGENT,
                                END, END
                        )
                )
                .addEdge(ClassificationRouter.LEGAL_AGENT, END)
                .addEdge(ClassificationRouter.MAINTENANCE_AGENT, END)
                .addEdge(ClassificationRouter.DISPUTE_AGENT, END);

        return graph.compile();
    }
}
