package ru.tkbbank.sbprouter.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.ExtractionResult;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RoutingDecisionEngineTest {
    @ParameterizedTest(name = "flagOff: {0} terminal={1} -> infosrv")
    @CsvSource({"ReqAuthPay, TKB_PAY", "ReqAuthPay, EXTERNAL", "ReqNoticePay, TKB_PAY", "ReqNoticePay, EXTERNAL"})
    void whenFlagOff_allGoToInfosrv(String requestType, TerminalOwner owner) {
        var engine = buildEngine(false);
        assertThat(engine.decide(new ExtractionResult(requestType, "c1", Map.of("state", "0"), Map.of()), owner).upstreamName()).isEqualTo("infosrv");
    }
    @Test void whenFlagOn_externalReqAuthPay_goesToInfosrv() {
        assertThat(buildEngine(true).decide(new ExtractionResult("ReqAuthPay", "c1", Map.of(), Map.of()), TerminalOwner.EXTERNAL).upstreamName()).isEqualTo("infosrv");
    }
    @Test void whenFlagOn_tkbPayReqAuthPay_goesToStubVerification() {
        assertThat(buildEngine(true).decide(new ExtractionResult("ReqAuthPay", "c1", Map.of(), Map.of()), TerminalOwner.TKB_PAY).upstreamName()).isEqualTo("stub-verification");
    }
    @Test void whenFlagOn_tkbPayReqNoticePayConfirm_goesToStubConnector() {
        assertThat(buildEngine(true).decide(new ExtractionResult("ReqNoticePay", "c1", Map.of("state", "0"), Map.of()), TerminalOwner.TKB_PAY).upstreamName()).isEqualTo("stub-connector");
    }
    @Test void whenFlagOn_tkbPayReqNoticePayCancel_goesToStubConnector() {
        assertThat(buildEngine(true).decide(new ExtractionResult("ReqNoticePay", "c1", Map.of("state", "-1"), Map.of()), TerminalOwner.TKB_PAY).upstreamName()).isEqualTo("stub-connector");
    }
    @Test void unknownRequestType_goesToInfosrv() {
        assertThat(buildEngine(true).decide(new ExtractionResult(null, "c1", Map.of(), Map.of()), TerminalOwner.EXTERNAL).upstreamName()).isEqualTo("infosrv");
    }
    @Test void c2bqrdRcv_alwaysGoesToStubC2bqrdVerification_evenWhenFlagOff() {
        var engine = buildEngine(false);
        var extraction = new ExtractionResult("ReqAuthPay", "c1", Map.of("sbpOperation", "C2BQRD_Rcv"), Map.of());
        assertThat(engine.decide(extraction, TerminalOwner.EXTERNAL).upstreamName()).isEqualTo("stub-c2bqrd-verification");
    }
    @Test void c2bqrdRcv_alwaysGoesToStubC2bqrdVerification_tkbPay() {
        var engine = buildEngine(true);
        var extraction = new ExtractionResult("ReqAuthPay", "c1", Map.of("sbpOperation", "C2BQRD_Rcv"), Map.of());
        assertThat(engine.decide(extraction, TerminalOwner.TKB_PAY).upstreamName()).isEqualTo("stub-c2bqrd-verification");
    }
    @Test void decisionContainsMetadata() {
        var d = buildEngine(true).decide(new ExtractionResult("ReqAuthPay", "c1", Map.of(), Map.of()), TerminalOwner.TKB_PAY);
        assertThat(d.terminalOwner()).isEqualTo(TerminalOwner.TKB_PAY);
        assertThat(d.requestType()).isEqualTo("ReqAuthPay");
    }
    private RoutingDecisionEngine buildEngine(boolean tkbPayEnabled) {
        var r = new SbpRouterProperties.Routing();
        r.setTkbPayEnabled(tkbPayEnabled);
        return new RoutingDecisionEngine(r);
    }
}
