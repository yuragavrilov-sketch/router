package ru.tkbbank.sbprouter.extraction;

import java.util.Map;

public record ExtractionResult(
        String requestType,
        String correlationId,
        Map<String, String> fields,
        Map<String, String> extraFields
) {
    public String field(String name) {
        return fields.getOrDefault(name, null);
    }
}
