package ru.tkbbank.sbprouter.stub;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class StubHandlerTest {
    @Autowired
    private WebTestClient webClient;

    @Test
    void stubVerification_returnsPositiveAnsAuthPay() {
        String response = webClient.post().uri("/stub/verification")
                .contentType(MediaType.APPLICATION_XML).bodyValue("<test/>")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(response).contains("<AnsAuthPay>").contains("<Code>0</Code>");
    }

    @Test
    void stubConnector_returnsAnsNoticePayWithBankOperId() {
        String response = webClient.post().uri("/stub/connector")
                .contentType(MediaType.APPLICATION_XML).bodyValue("<test/>")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(response).contains("<AnsNoticePay>").contains("<BankOperId>STUB-");
    }
}
