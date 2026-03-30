package ru.tkbbank.sbprouter.stub;

import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class StubHandler {
    private static final String ANS_AUTH_PAY_OK = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Document>
                <GCSvc>
                    <Payment>
                        <AnsAuthPay>
                            <Status>
                                <Code>0</Code>
                            </Status>
                        </AnsAuthPay>
                    </Payment>
                </GCSvc>
            </Document>""";

    private static final String ANS_AUTH_PAY_C2BQRD_TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Document stan="%s">
                <GCSvc>
                    <Payment>
                        <AnsAuthPay>
                            <Status>
                                <Code>0</Code>
                            </Status>
                            <BankOperId>%s</BankOperId>
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
                            <BankOperId>STUB-%d</BankOperId>
                        </AnsNoticePay>
                    </Payment>
                </GCSvc>
            </Document>""";

    @Bean
    public RouterFunction<ServerResponse> stubRoutes() {
        return RouterFunctions.route()
                .POST("/stub/verification", this::handleVerification)
                .POST("/stub/c2bqrd-verification", this::handleC2bqrdVerification)
                .POST("/stub/connector", this::handleConnector)
                .build();
    }

    private Mono<ServerResponse> handleVerification(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_XML).bodyValue(ANS_AUTH_PAY_OK);
    }

    private Mono<ServerResponse> handleC2bqrdVerification(ServerRequest request) {
        String stan = request.headers().firstHeader("X-Sbp-correlationId");
        String response = ANS_AUTH_PAY_C2BQRD_TEMPLATE.formatted(
                stan != null ? stan : "", UUID.randomUUID().toString());
        return ServerResponse.ok().contentType(MediaType.APPLICATION_XML).bodyValue(response);
    }

    private Mono<ServerResponse> handleConnector(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_XML)
                .bodyValue(ANS_NOTICE_PAY_TEMPLATE.formatted(System.currentTimeMillis()));
    }
}
