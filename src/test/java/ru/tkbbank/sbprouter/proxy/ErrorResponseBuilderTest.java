package ru.tkbbank.sbprouter.proxy;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseBuilderTest {
    private final ErrorResponseBuilder builder = new ErrorResponseBuilder();

    @Test void buildsAnsAuthPayError() {
        String xml = builder.buildErrorResponse("ReqAuthPay", "Connection refused");
        assertThat(xml).contains("<AnsAuthPay>").contains("<Code>-1</Code>")
                .contains("<LogMsg>Connection refused</LogMsg>").startsWith("<?xml");
    }
    @Test void buildsAnsNoticePayError() {
        String xml = builder.buildErrorResponse("ReqNoticePay", "Timeout");
        assertThat(xml).contains("<AnsNoticePay>").doesNotContain("<AnsAuthPay>")
                .contains("<BankOperId></BankOperId>");
    }
    @Test void unknownRequestType_buildsAnsAuthPayByDefault() {
        String xml = builder.buildErrorResponse(null, "Unknown error");
        assertThat(xml).contains("<AnsAuthPay>").contains("<Code>-1</Code>");
    }
    @Test void escapesXmlSpecialCharsInMessage() {
        String xml = builder.buildErrorResponse("ReqAuthPay", "Error <&> \"test\"");
        assertThat(xml).contains("&lt;&amp;&gt;").doesNotContain("<&>");
    }
}
