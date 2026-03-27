package ru.tkbbank.sbprouter.extraction;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class XmlFieldExtractorTest {

    private XmlFieldExtractor extractor;

    @BeforeEach
    void setUp() {
        var rules = new java.util.HashMap<String, SbpRouterProperties.ExtractionRuleSet>();

        var authRules = new SbpRouterProperties.ExtractionRuleSet();
        authRules.setRoutingFields(List.of(
                namedBlockRule("terminalName", "PayProfile", "Tran.TermName"),
                namedBlockRule("rcvTspId", "PayProfile", "RcvTSPId"),
                namedBlockRule("sbpOperation", "PayProfile", "SbpOperation"),
                namedBlockRule("sbpOperType", "PayProfile", "SbpOperType")
        ));
        authRules.setExtraFields(List.of(
                namedBlockRule("senderAccount", "PayProfile", "SndAccountNum"),
                pathRule("amount", "/Document/GCSvc/Payment/ReqAuthPay/Funds/Amount")
        ));
        rules.put("ReqAuthPay", authRules);

        var noticeRules = new SbpRouterProperties.ExtractionRuleSet();
        noticeRules.setRoutingFields(List.of(
                namedBlockRule("terminalName", "AdditionInfo", "Tran.TermName"),
                namedBlockRule("rcvTspId", "AdditionInfo", "RcvTSPId"),
                namedBlockRule("sbpOperation", "AdditionInfo", "SbpOperation"),
                pathRule("state", "/Document/GCSvc/Payment/ReqNoticePay/State")
        ));
        noticeRules.setExtraFields(List.of(
                pathRule("bankOperId", "/Document/GCSvc/Payment/ReqNoticePay/BankOperId")
        ));
        rules.put("ReqNoticePay", noticeRules);

        extractor = new XmlFieldExtractor(rules);
    }

    @Test
    void extractsFieldsFromB2cReqAuthPay() throws IOException {
        byte[] xml = loadFixture("test-xml/req-auth-pay-b2c.xml");
        ExtractionResult result = extractor.extract(xml);

        assertThat(result.requestType()).isEqualTo("ReqAuthPay");
        assertThat(result.correlationId()).isEqualTo("test-b2c-auth-001");
        assertThat(result.field("terminalName")).isEqualTo("PayTerminal01");
        assertThat(result.field("sbpOperType")).isEqualTo("B2C");
        assertThat(result.field("sbpOperation")).isEqualTo("B2COther_Snd");
        // extra fields are in extraFields(), not fields()
        assertThat(result.extraFields().get("senderAccount")).isEqualTo("40702810820100004437");
        assertThat(result.extraFields().get("amount")).isEqualTo("15787");
        // routing fields map should not contain extra fields
        assertThat(result.fields()).doesNotContainKey("senderAccount");
        assertThat(result.fields()).doesNotContainKey("amount");
    }

    @Test
    void extractsFieldsFromC2bReqAuthPay() throws IOException {
        byte[] xml = loadFixture("test-xml/req-auth-pay-c2b.xml");
        ExtractionResult result = extractor.extract(xml);

        assertThat(result.requestType()).isEqualTo("ReqAuthPay");
        assertThat(result.correlationId()).isEqualTo("test-c2b-auth-001");
        assertThat(result.field("rcvTspId")).isEqualTo("MB0000700543");
        assertThat(result.field("sbpOperType")).isEqualTo("C2BQRD");
    }

    @Test
    void extractsFieldsFromB2cReqNoticePayConfirm() throws IOException {
        byte[] xml = loadFixture("test-xml/req-notice-pay-b2c-confirm.xml");
        ExtractionResult result = extractor.extract(xml);

        assertThat(result.requestType()).isEqualTo("ReqNoticePay");
        assertThat(result.correlationId()).isEqualTo("test-b2c-notice-001");
        assertThat(result.field("terminalName")).isEqualTo("PayTerminal01");
        assertThat(result.field("state")).isEqualTo("0");
        // bankOperId is an extra field
        assertThat(result.extraFields().get("bankOperId")).isEqualTo("671920591533");
        assertThat(result.fields()).doesNotContainKey("bankOperId");
    }

    @Test
    void extractsStateFromCancelNoticePay() throws IOException {
        byte[] xml = loadFixture("test-xml/req-notice-pay-b2c-cancel.xml");
        ExtractionResult result = extractor.extract(xml);
        assertThat(result.field("state")).isEqualTo("-1");
    }

    @Test
    void returnsNullRequestTypeForUnknownRequest() throws IOException {
        byte[] xml = loadFixture("test-xml/unknown-request.xml");
        ExtractionResult result = extractor.extract(xml);

        assertThat(result.requestType()).isNull();
        assertThat(result.correlationId()).isEqualTo("test-unknown-001");
        assertThat(result.fields()).isEmpty();
        assertThat(result.extraFields()).isEmpty();
    }

    @Test
    void handlesFieldNotPresentInXml() throws IOException {
        byte[] xml = loadFixture("test-xml/req-auth-pay-b2c.xml");
        ExtractionResult result = extractor.extract(xml);
        assertThat(result.field("rcvTspId")).isNull();
    }

    private byte[] loadFixture(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }

    private FieldRule namedBlockRule(String name, String parent, String key) {
        var rule = new FieldRule();
        rule.setName(name);
        rule.setParent(parent);
        rule.setKey(key);
        return rule;
    }

    private FieldRule pathRule(String name, String path) {
        var rule = new FieldRule();
        rule.setName(name);
        rule.setPath(path);
        return rule;
    }
}
