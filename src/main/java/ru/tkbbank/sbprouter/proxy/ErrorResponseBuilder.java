package ru.tkbbank.sbprouter.proxy;

import org.springframework.stereotype.Component;

@Component
public class ErrorResponseBuilder {
    private static final String ANS_AUTH_PAY_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Document>
                <GCSvc>
                    <Payment>
                        <AnsAuthPay>
                            <Status>
                                <Code>-1</Code>
                                <LogMsg>%s</LogMsg>
                            </Status>
                        </AnsAuthPay>
                    </Payment>
                </GCSvc>
            </Document>""";

    private static final String ANS_NOTICE_PAY_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Document>
                <GCSvc>
                    <Payment>
                        <AnsNoticePay>
                            <BankOperId></BankOperId>
                            <Status>
                                <Code>-1</Code>
                                <LogMsg>%s</LogMsg>
                            </Status>
                        </AnsNoticePay>
                    </Payment>
                </GCSvc>
            </Document>""";

    public String buildErrorResponse(String requestType, String errorMessage) {
        String escaped = escapeXml(errorMessage);
        if ("ReqNoticePay".equals(requestType)) return ANS_NOTICE_PAY_TEMPLATE.formatted(escaped);
        return ANS_AUTH_PAY_TEMPLATE.formatted(escaped);
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
