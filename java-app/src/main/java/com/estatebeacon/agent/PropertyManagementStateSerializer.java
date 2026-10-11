package com.estatebeacon.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsc.langgraph4j.serializer.PlainTextStateSerializer;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link PropertyManagementState} as JSON via Jackson.
 *
 * <p>LangGraph4j's default {@code ObjectStreamStateSerializer} clones state between every graph
 * step using Java's built-in serialization, which requires every value ever placed in state to
 * implement {@link java.io.Serializable}. A JSON round trip carries no such requirement, so the
 * record types held in this state don't need to implement it either. The typed fields are
 * converted back from raw {@code Map}/JSON nodes explicitly below, using {@link
 * PropertyManagementState#TYPED_FIELDS}, because {@code PropertyManagementState}'s schema is
 * fixed and known, which avoids relying on LangGraph4j's generic (and unconfigured, by default)
 * polymorphic type tagging for values nested inside a {@code Map<String, Object>}.
 */
final class PropertyManagementStateSerializer extends PlainTextStateSerializer<PropertyManagementState> {

    private final ObjectMapper objectMapper = new ObjectMapper();

    PropertyManagementStateSerializer() {
        super(PropertyManagementState::new);
    }

    @Override
    public String writeDataAsString(Map<String, Object> data) throws IOException {
        return objectMapper.writeValueAsString(data);
    }

    @Override
    public Map<String, Object> readDataFromString(String json) throws IOException {
        Map<String, Object> raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        Map<String, Object> data = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            Class<?> type = PropertyManagementState.TYPED_FIELDS.get(entry.getKey());
            data.put(entry.getKey(), type != null ? objectMapper.convertValue(entry.getValue(), type) : entry.getValue());
        }
        return data;
    }
}
