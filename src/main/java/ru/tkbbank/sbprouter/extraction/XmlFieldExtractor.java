package ru.tkbbank.sbprouter.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class XmlFieldExtractor {

    private static final Logger log = LoggerFactory.getLogger(XmlFieldExtractor.class);

    private static final XMLInputFactory XML_INPUT_FACTORY = XMLInputFactory.newInstance();

    static {
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_COALESCING, true);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_INPUT_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private final Map<String, SbpRouterProperties.ExtractionRuleSet> rules;

    @Autowired
    public XmlFieldExtractor(SbpRouterProperties properties) {
        this.rules = properties.getExtractionRules();
    }

    XmlFieldExtractor(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        this.rules = rules;
    }

    public ExtractionResult extract(byte[] xmlBytes) {
        try {
            return doParse(xmlBytes);
        } catch (Exception e) {
            log.warn("Failed to parse XML: {}", e.getMessage());
            return new ExtractionResult(null, null, Map.of(), Map.of());
        }
    }

    private ExtractionResult doParse(byte[] xmlBytes) throws XMLStreamException {
        XMLStreamReader reader = XML_INPUT_FACTORY.createXMLStreamReader(
                new ByteArrayInputStream(xmlBytes));

        try {
            String correlationId = null;
            String requestType = null;
            List<FieldRule> activeRoutingRules = null;
            List<FieldRule> activeExtraRules = null;
            List<FieldRule> activeAllRules = null;
            Set<String> extraFieldNames = null;
            Map<String, String> routingFields = new HashMap<>();
            Map<String, String> extraFieldsMap = new HashMap<>();

            // Path tracking
            Deque<String> pathStack = new ArrayDeque<>();

            // Named block state
            String currentBlockParent = null;   // name of the block element currently inside (e.g. "PayProfile")
            String currentPNameID = null;       // text of last <PNameID> seen in current block
            boolean capturingPNameID = false;
            boolean capturingPValue = false;
            String pendingPValue = null;

            // Path rule capturing
            String capturingPathField = null;
            StringBuilder pathText = new StringBuilder();

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String localName = reader.getLocalName();
                        pathStack.addLast(localName);
                        String currentPath = buildPath(pathStack);

                        // Read stan from <Document>
                        if ("Document".equals(localName)) {
                            correlationId = reader.getAttributeValue(null, "stan");
                        }

                        // Detect request type: first child of <Payment>
                        if (requestType == null && pathStack.size() == 4) {
                            // /Document/GCSvc/Payment/<RequestType>
                            String parent = getParentName(pathStack);
                            if ("Payment".equals(parent)) {
                                String candidate = localName;
                                if (rules != null && rules.containsKey(candidate)) {
                                    requestType = candidate;
                                    SbpRouterProperties.ExtractionRuleSet ruleSet = rules.get(candidate);
                                    activeRoutingRules = ruleSet.getRoutingFields() != null ? ruleSet.getRoutingFields() : List.of();
                                    activeExtraRules = ruleSet.getExtraFields() != null ? ruleSet.getExtraFields() : List.of();
                                    activeAllRules = new ArrayList<>(activeRoutingRules);
                                    activeAllRules.addAll(activeExtraRules);
                                    Set<String> extraNames = new java.util.HashSet<>();
                                    for (FieldRule r : activeExtraRules) {
                                        extraNames.add(r.getName());
                                    }
                                    extraFieldNames = extraNames;
                                } else {
                                    // Unknown request type — no rules
                                    requestType = null;
                                    activeAllRules = null;
                                }
                            }
                        }

                        if (activeAllRules == null) break;

                        // Check if entering a named block parent element
                        for (FieldRule rule : activeAllRules) {
                            if (rule.isNamedBlock() && rule.getParent().equals(localName)) {
                                currentBlockParent = localName;
                                currentPNameID = null;
                                break;
                            }
                        }

                        // Inside a named block: detect <PNameID> and <PValue>
                        if (currentBlockParent != null) {
                            if ("PNameID".equals(localName)) {
                                capturingPNameID = true;
                                currentPNameID = null;
                            } else if ("PValue".equals(localName)) {
                                capturingPValue = true;
                                pendingPValue = null;
                            }
                        }

                        // Check path rules
                        capturingPathField = null;
                        for (FieldRule rule : activeAllRules) {
                            if (!rule.isNamedBlock() && rule.getPath() != null) {
                                if (currentPath.equals(rule.getPath())) {
                                    capturingPathField = rule.getName();
                                    pathText.setLength(0);
                                    break;
                                }
                            }
                        }
                    }

                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        String text = reader.getText();

                        if (capturingPNameID) {
                            currentPNameID = (currentPNameID == null ? "" : currentPNameID) + text;
                        } else if (capturingPValue) {
                            pendingPValue = (pendingPValue == null ? "" : pendingPValue) + text;
                        }

                        if (capturingPathField != null) {
                            pathText.append(text);
                        }
                    }

                    case XMLStreamConstants.END_ELEMENT -> {
                        String localName = reader.getLocalName();

                        // Finalize PNameID capture
                        if (capturingPNameID && "PNameID".equals(localName)) {
                            capturingPNameID = false;
                        }

                        // Finalize PValue capture — try to match against rules
                        if (capturingPValue && "PValue".equals(localName)) {
                            capturingPValue = false;
                            if (currentBlockParent != null && currentPNameID != null && activeAllRules != null) {
                                String keyToMatch = currentPNameID;
                                String value = pendingPValue != null ? pendingPValue : "";
                                for (FieldRule rule : activeAllRules) {
                                    if (rule.isNamedBlock()
                                            && rule.getParent().equals(currentBlockParent)
                                            && rule.getKey().equals(keyToMatch)) {
                                        if (extraFieldNames != null && extraFieldNames.contains(rule.getName())) {
                                            extraFieldsMap.put(rule.getName(), value);
                                        } else {
                                            routingFields.put(rule.getName(), value);
                                        }
                                        break;
                                    }
                                }
                            }
                            pendingPValue = null;
                        }

                        // Finalize path rule capture
                        if (capturingPathField != null && localName.equals(pathStack.peekLast())) {
                            if (extraFieldNames != null && extraFieldNames.contains(capturingPathField)) {
                                extraFieldsMap.put(capturingPathField, pathText.toString());
                            } else {
                                routingFields.put(capturingPathField, pathText.toString());
                            }
                            capturingPathField = null;
                        }

                        // Exit named block
                        if (localName.equals(currentBlockParent)) {
                            currentBlockParent = null;
                            currentPNameID = null;
                            capturingPNameID = false;
                            capturingPValue = false;
                        }

                        pathStack.pollLast();
                    }
                }
            }

            // If requestType was a candidate but not in rules, set to null
            return new ExtractionResult(requestType, correlationId, Map.copyOf(routingFields), Map.copyOf(extraFieldsMap));
        } finally {
            reader.close();
        }
    }

    private String buildPath(Deque<String> stack) {
        StringBuilder sb = new StringBuilder();
        for (String segment : stack) {
            sb.append('/').append(segment);
        }
        return sb.toString();
    }

    private String getParentName(Deque<String> stack) {
        // Returns the second-to-last element
        List<String> list = new ArrayList<>(stack);
        if (list.size() < 2) return null;
        return list.get(list.size() - 2);
    }
}
