package ru.tkbbank.sbprouter.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class TerminalDetectorTest {
    private TerminalDetector detector;

    @BeforeEach
    void setUp() {
        var terminals = new SbpRouterProperties.Terminals();
        terminals.setTkbPayList(List.of("MB0000700543", "MB0000004185"));
        var c2b = new SbpRouterProperties.C2bTerminal();
        c2b.setFieldName("rcvTspId");
        terminals.setC2bTerminal(c2b);
        var b2c = new SbpRouterProperties.B2cTerminal();
        b2c.setFieldName("terminalName");
        b2c.setTkbPayPrefix("Pay");
        terminals.setB2cTerminal(b2c);
        detector = new TerminalDetector(terminals);
    }

    @Test void c2bTerminalInList_returnsTkbPay() {
        assertThat(detector.detect(Map.of("sbpOperType", "C2BQRD", "rcvTspId", "MB0000700543"))).isEqualTo(TerminalOwner.TKB_PAY);
    }
    @Test void c2bTerminalNotInList_returnsExternal() {
        assertThat(detector.detect(Map.of("sbpOperType", "C2BQRD", "rcvTspId", "MB9999999999"))).isEqualTo(TerminalOwner.EXTERNAL);
    }
    @Test void b2cTerminalWithPayPrefix_returnsTkbPay() {
        assertThat(detector.detect(Map.of("sbpOperType", "B2C", "terminalName", "PayTerminal01"))).isEqualTo(TerminalOwner.TKB_PAY);
    }
    @Test void b2cTerminalWithoutPayPrefix_returnsExternal() {
        assertThat(detector.detect(Map.of("sbpOperType", "B2C", "terminalName", "SB01133"))).isEqualTo(TerminalOwner.EXTERNAL);
    }
    @Test void missingSbpOperType_returnsExternal() {
        assertThat(detector.detect(Map.of("terminalName", "PayTerminal01"))).isEqualTo(TerminalOwner.EXTERNAL);
    }
    @Test void unknownSbpOperType_returnsExternal() {
        assertThat(detector.detect(Map.of("sbpOperType", "UNKNOWN", "terminalName", "PayTerminal01"))).isEqualTo(TerminalOwner.EXTERNAL);
    }
    @Test void c2bMissingRcvTspId_returnsExternal() {
        assertThat(detector.detect(Map.of("sbpOperType", "C2BQRD"))).isEqualTo(TerminalOwner.EXTERNAL);
    }
}
