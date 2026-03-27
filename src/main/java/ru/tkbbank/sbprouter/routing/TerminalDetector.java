package ru.tkbbank.sbprouter.routing;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import java.util.Map;
import java.util.Set;

@Component
public class TerminalDetector {
    private final Set<String> tkbPayList;
    private final String c2bFieldName;
    private final String b2cFieldName;
    private final String b2cPrefix;

    @Autowired
    public TerminalDetector(SbpRouterProperties properties) {
        this(properties.getTerminals());
    }

    TerminalDetector(SbpRouterProperties.Terminals terminals) {
        this.tkbPayList = Set.copyOf(terminals.getTkbPayList());
        this.c2bFieldName = terminals.getC2bTerminal().getFieldName();
        this.b2cFieldName = terminals.getB2cTerminal().getFieldName();
        this.b2cPrefix = terminals.getB2cTerminal().getTkbPayPrefix();
    }

    public TerminalOwner detect(Map<String, String> fields) {
        String operType = fields.get("sbpOperType");
        if (operType == null) return TerminalOwner.EXTERNAL;
        if (operType.toUpperCase().startsWith("C2B")) {
            String tspId = fields.get(c2bFieldName);
            return (tspId != null && tkbPayList.contains(tspId)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        if (operType.toUpperCase().startsWith("B2C")) {
            String termName = fields.get(b2cFieldName);
            return (termName != null && termName.startsWith(b2cPrefix)) ? TerminalOwner.TKB_PAY : TerminalOwner.EXTERNAL;
        }
        return TerminalOwner.EXTERNAL;
    }
}
