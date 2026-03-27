package ru.tkbbank.sbprouter.stub;

import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;

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
                .POST("/stub/connector", this::handleConnector)
                .build();
    }

    private Mono<ServerResponse> handleVerification(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_XML).bodyValue(ANS_AUTH_PAY_OK);
    }

    private Mono<ServerResponse> handleConnector(ServerRequest request) {
        return ServerResponse.ok().contentType(MediaType.APPLICATION_XML)
                .bodyValue(ANS_NOTICE_PAY_TEMPLATE.formatted(System.currentTimeMillis()));
    }
}
