package com.estatebeacon.agent;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * Constructs and compiles the LangGraph4j property management pipeline. Mirrors {@code agents.py:build_graph}.
 *
 * <p>The graph's structure (nodes, edges) never changes at runtime, so it's compiled exactly
 * once here, at bean construction — a {@code GraphStateException} at startup means the graph
 * itself is malformed and fails fast, rather than surfacing per-email later. The same
 * {@link CompiledGraph} is then reused for every {@code invoke()} call.
 */
@Component
public class EstateBeaconGraph {

    private final CompiledGraph<PropertyManagementState> compiledGraph;

    public EstateBeaconGraph(
            SupervisorNode supervisorNode,
            LegalAgentNode legalAgentNode,
            MaintenanceAgentNode maintenanceAgentNode,
            DisputeAgentNode disputeAgentNode
    ) throws GraphStateException {
        StateGraph<PropertyManagementState> graph = new StateGraph<>(new PropertyManagementStateSerializer())
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

        this.compiledGraph = graph.compile();
    }

    public CompiledGraph<PropertyManagementState> compiledGraph() {
        return compiledGraph;
    }
}
