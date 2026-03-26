package ru.tkbbank.sbprouter.extraction;

import java.util.Map;

public record ExtractionResult(
        String requestType,     // "ReqAuthPay", "ReqNoticePay", or null
        String correlationId,   // stan attribute from <Document>
        Map<String, String> fields  // extracted field name → value
) {
    public String field(String name) {
        return fields.getOrDefault(name, null);
    }
}
