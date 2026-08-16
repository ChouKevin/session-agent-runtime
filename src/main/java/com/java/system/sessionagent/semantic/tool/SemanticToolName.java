package com.java.system.sessionagent.semantic.tool;

import com.java.system.sessionagent.tool.domain.ToolName;

public enum SemanticToolName {
    LIST_ENTRY_POINTS("codebase_list_entry_points"),
    LOOKUP_API_ROUTE("codebase_lookup_api_route"),
    SUGGEST_API_ROUTE("codebase_suggest_api_route"),
    OUTGOING_CALL_GRAPH("codebase_outgoing_call_graph"),
    INCOMING_CALL_GRAPH("codebase_incoming_call_graph"),
    DISCOVER_CONCEPTS("codebase_discover_concepts"),
    RESOLVE_CONCEPT("codebase_resolve_concept"),
    DISCOVER_EVENT_LISTENERS("codebase_discover_event_listeners"),
    DISCOVER_METHOD_IMPLEMENTATIONS("codebase_discover_method_implementations"),
    DISCOVER_TYPE_MEMBERS("codebase_discover_type_members"),
    FIND_INTERNAL_REFERENCES("codebase_find_internal_references"),
    GET_EVIDENCE_SOURCE("codebase_get_evidence_source"),
    GET_METHOD_SOURCE("codebase_get_method_source"),
    GET_SOURCE_SEGMENT("codebase_get_source_segment"),
    RESOLVE_SOURCE_SYMBOL("codebase_resolve_source_symbol");

    private final ToolName toolName;

    SemanticToolName(String value) {
        this.toolName = new ToolName(value);
    }

    public ToolName toolName() {
        return toolName;
    }
}
