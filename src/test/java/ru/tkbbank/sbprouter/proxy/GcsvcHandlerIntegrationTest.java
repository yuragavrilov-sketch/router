package ru.tkbbank.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GcsvcHandlerIntegrationTest {

    static WireMockServer wireMock = new WireMockServer(0);

    @Autowired
    private WebTestClient webClient;

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        wireMock.start();
        registry.add("sbp-router.upstreams.infosrv.url", () -> wireMock.baseUrl() + "/api/gcsvc");
    }

    @AfterAll
    static void tearDown() {
        wireMock.stop();
    }

    @BeforeEach
    void resetMocks() {
        wireMock.resetAll();
    }

    @Test
    void proxiesReqAuthPayToInfosrv_whenFlagOff() throws IOException {
        String responseXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document><GCSvc><Payment><AnsAuthPay>
                <Status><Code>0</Code></Status>
                </AnsAuthPay></Payment></GCSvc></Document>""";

        wireMock.stubFor(post(urlEqualTo("/api/gcsvc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(responseXml)));

        byte[] requestXml = loadFixture("test-xml/req-auth-pay-b2c.xml");

        String body = webClient.post()
                .uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("<Code>0</Code>"));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    @Test
    void proxiesC2bReqNoticePayToInfosrv() throws IOException {
        String responseXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document><GCSvc><Payment><AnsNoticePay>
                <BankOperId>12345</BankOperId>
                </AnsNoticePay></Payment></GCSvc></Document>""";

        wireMock.stubFor(post(urlEqualTo("/api/gcsvc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(responseXml)));

        byte[] requestXml = loadFixture("test-xml/req-notice-pay-c2b.xml");

        String body = webClient.post()
                .uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertTrue(body.contains("<BankOperId>12345</BankOperId>"));
    }

    @Test
    void forwardsExtraFieldsAsHeaders() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<Document><GCSvc><Payment><AnsAuthPay><Status><Code>0</Code></Status></AnsAuthPay></Payment></GCSvc></Document>")));

        byte[] requestXml = loadFixture("test-xml/req-auth-pay-b2c.xml");

        webClient.post()
                .uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/gcsvc"))
                .withHeader("X-Sbp-senderAccount", equalTo("40702810820100004437"))
                .withHeader("X-Sbp-amount", equalTo("15787")));
    }

    @Test
    void returnsErrorXml_whenUpstreamUnavailable() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        byte[] requestXml = loadFixture("test-xml/req-auth-pay-b2c.xml");

        String body = webClient.post()
                .uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("<Code>-1</Code>"));
        Assertions.assertTrue(body.contains("<AnsAuthPay>"));
    }

    @Test
    void proxiesUnknownRequestToInfosrv() throws IOException {
        wireMock.stubFor(post(urlEqualTo("/api/gcsvc"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody("<ok/>")));

        byte[] requestXml = loadFixture("test-xml/unknown-request.xml");

        webClient.post()
                .uri("/api/gcsvc")
                .contentType(MediaType.APPLICATION_XML)
                .accept(MediaType.APPLICATION_XML)
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk();

        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/gcsvc")));
    }

    private byte[] loadFixture(String path) throws IOException {
        try (var is = getClass().getClassLoader().getResourceAsStream(path)) {
            return is.readAllBytes();
        }
    }
}
