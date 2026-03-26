# SBP Proxy Router Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reactive HTTP proxy that routes GCSvc XML requests from the SBP Platform to InfoSRV or stub modules based on configurable field extraction, terminal ownership, and a feature flag.

**Architecture:** Single Spring WebFlux service. StAX-based configurable XML parser extracts routing fields in a single pass. Routing Decision Engine determines upstream (InfoSRV or stub) based on request type, terminal ownership (C2B list / B2C prefix), and a feature flag. WebClient proxies original XML as-is.

**Tech Stack:** Java 17+, Spring Boot 3.x, Spring WebFlux, StAX, WebClient, Micrometer/Prometheus, SLF4J/Logback, WireMock (tests), JUnit 5

---

## File Structure

```
sbp-router/
├── pom.xml
├── src/main/java/ru/tkbbank/sbprouter/
│   ├── SbpRouterApplication.java              — Spring Boot entry point
│   ├── config/
│   │   ├── SbpRouterProperties.java           — @ConfigurationProperties root
│   │   └── WebClientConfig.java               — WebClient beans per upstream
│   ├── extraction/
│   │   ├── FieldRule.java                     — POJO: one extraction rule (name, parent, key, path)
│   │   ├── ExtractionResult.java              — Record: requestType + Map<String,String> fields
│   │   └── XmlFieldExtractor.java             — StAX single-pass parser
│   ├── routing/
│   │   ├── TerminalOwner.java                 — Enum: TKB_PAY, EXTERNAL
│   │   ├── RouteDecision.java                 — Record: upstream name + URL
│   │   ├── TerminalDetector.java              — C2B list / B2C prefix logic
│   │   └── RoutingDecisionEngine.java         — Combines extractor + terminal + flag → route
│   ├── proxy/
│   │   ├── GcsvcHandler.java                  — Main handler: POST /api/gcsvc
│   │   ├── GcsvcRouter.java                   — WebFlux RouterFunction bean
│   │   ├── ProxyClient.java                   — WebClient proxy to upstream
│   │   └── ErrorResponseBuilder.java          — Builds AnsAuthPay/AnsNoticePay error XML
│   ├── stub/
│   │   └── StubHandler.java                   — Stub responses for Verification & Connector
│   └── observability/
│       └── MetricsService.java                — Micrometer counters/timers
├── src/main/resources/
│   ├── application.yml                        — Full config
│   └── logback-spring.xml                     — JSON structured logging
├── src/test/java/ru/tkbbank/sbprouter/
│   ├── extraction/
│   │   └── XmlFieldExtractorTest.java
│   ├── routing/
│   │   ├── TerminalDetectorTest.java
│   │   └── RoutingDecisionEngineTest.java
│   ├── proxy/
│   │   ├── ErrorResponseBuilderTest.java
│   │   └── GcsvcHandlerIntegrationTest.java
│   ├── stub/
│   │   └── StubHandlerTest.java
│   └── config/
│       └── SbpRouterPropertiesTest.java
├── src/test/resources/
│   ├── application-test.yml
│   └── test-xml/
│       ├── req-auth-pay-b2c.xml
│       ├── req-auth-pay-c2b.xml
│       ├── req-notice-pay-b2c-confirm.xml
│       ├── req-notice-pay-b2c-cancel.xml
│       ├── req-notice-pay-c2b.xml
│       └── unknown-request.xml
```

---

## Task 1: Project Scaffolding

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/ru/tkbbank/sbprouter/SbpRouterApplication.java`

- [ ] **Step 1: Create `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.3</version>
        <relativePath/>
    </parent>

    <groupId>ru.tkbbank</groupId>
    <artifactId>sbp-router</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <name>sbp-router</name>
    <description>SBP Proxy Router for C2B and B2C operations</description>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencies>
        <!-- WebFlux -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Actuator + Prometheus -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>

        <!-- Structured JSON logging -->
        <dependency>
            <groupId>net.logstash.logback</groupId>
            <artifactId>logstash-logback-encoder</artifactId>
            <version>8.0</version>
        </dependency>

        <!-- Config properties binding -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>io.projectreactor</groupId>
            <artifactId>reactor-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-standalone</artifactId>
            <version>3.10.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create application entry point**

```java
// src/main/java/ru/tkbbank/sbprouter/SbpRouterApplication.java
package ru.tkbbank.sbprouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SbpRouterApplication {
    public static void main(String[] args) {
        SpringApplication.run(SbpRouterApplication.class, args);
    }
}
```

- [ ] **Step 3: Create minimal `application.yml`**

```yaml
# src/main/resources/application.yml
server:
  port: 8080
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: sbp-router
```

- [ ] **Step 4: Verify project compiles**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git init
git add pom.xml src/main/java/ru/tkbbank/sbprouter/SbpRouterApplication.java src/main/resources/application.yml
git commit -m "feat: scaffold Spring Boot WebFlux project"
```

---

## Task 2: Configuration Properties

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/extraction/FieldRule.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/config/SbpRouterPropertiesTest.java`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: Create `FieldRule` POJO**

```java
// src/main/java/ru/tkbbank/sbprouter/extraction/FieldRule.java
package ru.tkbbank.sbprouter.extraction;

public class FieldRule {
    private String name;
    private String parent; // e.g. "PayProfile", "AdditionInfo"
    private String key;    // e.g. "Tran.TermName" (PNameID value)
    private String path;   // e.g. "/Document/GCSvc/Payment/ReqAuthPay/Funds/Amount"

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public boolean isNamedBlock() {
        return parent != null && key != null;
    }
}
```

- [ ] **Step 2: Create `SbpRouterProperties`**

```java
// src/main/java/ru/tkbbank/sbprouter/config/SbpRouterProperties.java
package ru.tkbbank.sbprouter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.extraction.FieldRule;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "sbp-router")
public class SbpRouterProperties {

    private Map<String, ExtractionRuleSet> extractionRules;
    private Terminals terminals = new Terminals();
    private Routing routing = new Routing();
    private Map<String, UpstreamConfig> upstreams;

    // --- getters/setters ---
    public Map<String, ExtractionRuleSet> getExtractionRules() { return extractionRules; }
    public void setExtractionRules(Map<String, ExtractionRuleSet> extractionRules) { this.extractionRules = extractionRules; }
    public Terminals getTerminals() { return terminals; }
    public void setTerminals(Terminals terminals) { this.terminals = terminals; }
    public Routing getRouting() { return routing; }
    public void setRouting(Routing routing) { this.routing = routing; }
    public Map<String, UpstreamConfig> getUpstreams() { return upstreams; }
    public void setUpstreams(Map<String, UpstreamConfig> upstreams) { this.upstreams = upstreams; }

    public static class ExtractionRuleSet {
        private List<FieldRule> routingFields;
        private List<FieldRule> extraFields;

        public List<FieldRule> getRoutingFields() { return routingFields; }
        public void setRoutingFields(List<FieldRule> routingFields) { this.routingFields = routingFields; }
        public List<FieldRule> getExtraFields() { return extraFields; }
        public void setExtraFields(List<FieldRule> extraFields) { this.extraFields = extraFields; }

        public List<FieldRule> allFields() {
            var all = new java.util.ArrayList<>(routingFields != null ? routingFields : List.of());
            if (extraFields != null) all.addAll(extraFields);
            return all;
        }
    }

    public static class Terminals {
        private C2bTerminal c2bTerminal = new C2bTerminal();
        private B2cTerminal b2cTerminal = new B2cTerminal();
        private List<String> tkbPayList = List.of();

        public C2bTerminal getC2bTerminal() { return c2bTerminal; }
        public void setC2bTerminal(C2bTerminal c2bTerminal) { this.c2bTerminal = c2bTerminal; }
        public B2cTerminal getB2cTerminal() { return b2cTerminal; }
        public void setB2cTerminal(B2cTerminal b2cTerminal) { this.b2cTerminal = b2cTerminal; }
        public List<String> getTkbPayList() { return tkbPayList; }
        public void setTkbPayList(List<String> tkbPayList) { this.tkbPayList = tkbPayList; }
    }

    public static class C2bTerminal {
        private String fieldName = "rcvTspId";

        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
    }

    public static class B2cTerminal {
        private String fieldName = "terminalName";
        private String tkbPayPrefix = "Pay";

        public String getFieldName() { return fieldName; }
        public void setFieldName(String fieldName) { this.fieldName = fieldName; }
        public String getTkbPayPrefix() { return tkbPayPrefix; }
        public void setTkbPayPrefix(String tkbPayPrefix) { this.tkbPayPrefix = tkbPayPrefix; }
    }

    public static class Routing {
        private boolean tkbPayEnabled = false;

        public boolean isTkbPayEnabled() { return tkbPayEnabled; }
        public void setTkbPayEnabled(boolean tkbPayEnabled) { this.tkbPayEnabled = tkbPayEnabled; }
    }

    public static class UpstreamConfig {
        private String url;
        private Duration timeout = Duration.ofSeconds(30);
        private RetryConfig retry = new RetryConfig();

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public RetryConfig getRetry() { return retry; }
        public void setRetry(RetryConfig retry) { this.retry = retry; }
    }

    public static class RetryConfig {
        private int maxAttempts = 2;
        private Duration backoff = Duration.ofMillis(500);

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public Duration getBackoff() { return backoff; }
        public void setBackoff(Duration backoff) { this.backoff = backoff; }
    }
}
```

- [ ] **Step 3: Create test config `application-test.yml`**

```yaml
# src/test/resources/application-test.yml
sbp-router:
  extraction-rules:
    ReqAuthPay:
      routing-fields:
        - name: terminalName
          parent: PayProfile
          key: Tran.TermName
        - name: rcvTspId
          parent: PayProfile
          key: RcvTSPId
        - name: sbpOperation
          parent: PayProfile
          key: SbpOperation
        - name: sbpOperType
          parent: PayProfile
          key: SbpOperType
      extra-fields:
        - name: senderAccount
          parent: PayProfile
          key: SndAccountNum
        - name: amount
          path: /Document/GCSvc/Payment/ReqAuthPay/Funds/Amount
    ReqNoticePay:
      routing-fields:
        - name: terminalName
          parent: AdditionInfo
          key: Tran.TermName
        - name: rcvTspId
          parent: AdditionInfo
          key: RcvTSPId
        - name: sbpOperation
          parent: AdditionInfo
          key: SbpOperation
        - name: state
          path: /Document/GCSvc/Payment/ReqNoticePay/State
      extra-fields:
        - name: bankOperId
          path: /Document/GCSvc/Payment/ReqNoticePay/BankOperId

  terminals:
    c2b-terminal:
      field-name: rcvTspId
    b2c-terminal:
      field-name: terminalName
      tkb-pay-prefix: "Pay"
    tkb-pay-list:
      - MB0000700543
      - MB0000004185

  routing:
    tkb-pay-enabled: false

  upstreams:
    infosrv:
      url: http://localhost:9999/api/gcsvc
      timeout: 5s
      retry:
        max-attempts: 1
        backoff: 100ms
    stub-verification:
      url: http://localhost:${server.port}/stub/verification
    stub-connector:
      url: http://localhost:${server.port}/stub/connector
```

- [ ] **Step 4: Write properties binding test**

```java
// src/test/java/ru/tkbbank/sbprouter/config/SbpRouterPropertiesTest.java
package ru.tkbbank.sbprouter.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class SbpRouterPropertiesTest {

    @Autowired
    private SbpRouterProperties props;

    @Test
    void extractionRulesLoaded() {
        assertThat(props.getExtractionRules()).containsKeys("ReqAuthPay", "ReqNoticePay");

        var authRules = props.getExtractionRules().get("ReqAuthPay");
        assertThat(authRules.getRoutingFields()).hasSize(4);
        assertThat(authRules.getExtraFields()).hasSize(2);
        assertThat(authRules.getRoutingFields().get(0).getName()).isEqualTo("terminalName");
        assertThat(authRules.getRoutingFields().get(0).isNamedBlock()).isTrue();

        var noticeRules = props.getExtractionRules().get("ReqNoticePay");
        assertThat(noticeRules.getRoutingFields()).hasSize(4);
        assertThat(noticeRules.getRoutingFields().get(3).getPath())
                .isEqualTo("/Document/GCSvc/Payment/ReqNoticePay/State");
    }

    @Test
    void terminalsConfigLoaded() {
        assertThat(props.getTerminals().getTkbPayList()).contains("MB0000700543", "MB0000004185");
        assertThat(props.getTerminals().getC2bTerminal().getFieldName()).isEqualTo("rcvTspId");
        assertThat(props.getTerminals().getB2cTerminal().getFieldName()).isEqualTo("terminalName");
        assertThat(props.getTerminals().getB2cTerminal().getTkbPayPrefix()).isEqualTo("Pay");
    }

    @Test
    void routingConfigLoaded() {
        assertThat(props.getRouting().isTkbPayEnabled()).isFalse();
    }

    @Test
    void upstreamsConfigLoaded() {
        assertThat(props.getUpstreams()).containsKeys("infosrv", "stub-verification", "stub-connector");
        assertThat(props.getUpstreams().get("infosrv").getTimeout().getSeconds()).isEqualTo(5);
        assertThat(props.getUpstreams().get("infosrv").getRetry().getMaxAttempts()).isEqualTo(1);
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -pl . -Dtest=SbpRouterPropertiesTest -q`
Expected: 4 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/config/ src/main/java/ru/tkbbank/sbprouter/extraction/FieldRule.java src/test/
git commit -m "feat: add configuration properties with YAML binding"
```

---

## Task 3: Test XML Fixtures

**Files:**
- Create: `src/test/resources/test-xml/req-auth-pay-b2c.xml`
- Create: `src/test/resources/test-xml/req-auth-pay-c2b.xml`
- Create: `src/test/resources/test-xml/req-notice-pay-b2c-confirm.xml`
- Create: `src/test/resources/test-xml/req-notice-pay-b2c-cancel.xml`
- Create: `src/test/resources/test-xml/req-notice-pay-c2b.xml`
- Create: `src/test/resources/test-xml/unknown-request.xml`

- [ ] **Step 1: Create B2C ReqAuthPay fixture**

```xml
<!-- src/test/resources/test-xml/req-auth-pay-b2c.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-b2c-auth-001">
    <GCSvc version="1">
        <Payment>
            <ReqAuthPay>
                <SenderId>b2cTr-wp-5979</SenderId>
                <PayType>PayFromAccount</PayType>
                <ReqType>ReqPay</ReqType>
                <PayProfile>
                    <PNameID>Tran.TermName</PNameID>
                    <PValue>PayTerminal01</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SbpOperation</PNameID>
                    <PValue>B2COther_Snd</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SbpOperType</PNameID>
                    <PValue>B2C</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SndAccountNum</PNameID>
                    <PValue>40702810820100004437</PValue>
                </PayProfile>
                <Funds>
                    <Amount>15787</Amount>
                    <Cur>810</Cur>
                </Funds>
            </ReqAuthPay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 2: Create C2B ReqAuthPay fixture**

```xml
<!-- src/test/resources/test-xml/req-auth-pay-c2b.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-c2b-auth-001">
    <GCSvc version="1">
        <Payment>
            <ReqAuthPay>
                <SenderId>c2b-001</SenderId>
                <PayType>PayToAccount</PayType>
                <ReqType>ReqPay</ReqType>
                <PayProfile>
                    <PNameID>Tran.TermName</PNameID>
                    <PValue>SB01133</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>RcvTSPId</PNameID>
                    <PValue>MB0000700543</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SbpOperation</PNameID>
                    <PValue>C2BQRD_Rcv</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SbpOperType</PNameID>
                    <PValue>C2BQRD</PValue>
                </PayProfile>
                <PayProfile>
                    <PNameID>SndAccountNum</PNameID>
                    <PValue>40817810510008408628</PValue>
                </PayProfile>
                <Funds>
                    <Amount>29201</Amount>
                    <Cur>810</Cur>
                </Funds>
            </ReqAuthPay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 3: Create B2C ReqNoticePay confirm fixture (State=0)**

```xml
<!-- src/test/resources/test-xml/req-notice-pay-b2c-confirm.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-b2c-notice-001">
    <GCSvc version="1">
        <Payment>
            <ReqNoticePay>
                <SenderId>b2cTr-wp-5979</SenderId>
                <BankOperId>671920591533</BankOperId>
                <State>0</State>
                <AdditionInfo>
                    <PNameID>Tran.TermName</PNameID>
                    <PValue>PayTerminal01</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperation</PNameID>
                    <PValue>B2COther_Snd</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperType</PNameID>
                    <PValue>B2C</PValue>
                </AdditionInfo>
                <Funds>
                    <Amount>15787</Amount>
                    <Cur>810</Cur>
                </Funds>
            </ReqNoticePay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 4: Create B2C ReqNoticePay cancel fixture (State=-1)**

```xml
<!-- src/test/resources/test-xml/req-notice-pay-b2c-cancel.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-b2c-cancel-001">
    <GCSvc version="1">
        <Payment>
            <ReqNoticePay>
                <SenderId>b2cTr-wp-5979</SenderId>
                <BankOperId></BankOperId>
                <State>-1</State>
                <AdditionInfo>
                    <PNameID>Tran.TermName</PNameID>
                    <PValue>PayTerminal01</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperation</PNameID>
                    <PValue>B2COther_Snd</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperType</PNameID>
                    <PValue>B2C</PValue>
                </AdditionInfo>
            </ReqNoticePay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 5: Create C2B ReqNoticePay fixture**

```xml
<!-- src/test/resources/test-xml/req-notice-pay-c2b.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-c2b-notice-001">
    <GCSvc version="1">
        <Payment>
            <ReqNoticePay>
                <SenderId>B5169130048281010000120011530503</SenderId>
                <BankOperId></BankOperId>
                <State>0</State>
                <AdditionInfo>
                    <PNameID>Tran.TermName</PNameID>
                    <PValue>SB01133</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>RcvTSPId</PNameID>
                    <PValue>MB0000700543</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperation</PNameID>
                    <PValue>C2BQRD_Rcv</PValue>
                </AdditionInfo>
                <AdditionInfo>
                    <PNameID>SbpOperType</PNameID>
                    <PValue>C2BQRD</PValue>
                </AdditionInfo>
                <Funds>
                    <Amount>29201</Amount>
                    <Cur>810</Cur>
                </Funds>
            </ReqNoticePay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 6: Create unknown request fixture**

```xml
<!-- src/test/resources/test-xml/unknown-request.xml -->
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Document stan="test-unknown-001">
    <GCSvc version="1">
        <Payment>
            <ReqStatusPay>
                <SenderId>status-001</SenderId>
            </ReqStatusPay>
        </Payment>
    </GCSvc>
</Document>
```

- [ ] **Step 7: Commit**

```bash
git add src/test/resources/test-xml/
git commit -m "test: add XML fixture files for all request scenarios"
```

---

## Task 4: XML Field Extractor

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/extraction/ExtractionResult.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractor.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractorTest.java`

- [ ] **Step 1: Create `ExtractionResult` record**

```java
// src/main/java/ru/tkbbank/sbprouter/extraction/ExtractionResult.java
package ru.tkbbank.sbprouter.extraction;

import java.util.Map;

public record ExtractionResult(
        String requestType,     // "ReqAuthPay", "ReqNoticePay", or null
        String correlationId,   // stan attribute from <Document>
        Map<String, String> fields  // extracted field name → value
) {
    public String field(String name) {
        return fields.getOrDefault(name, null);
    }
}
```

- [ ] **Step 2: Write failing tests for `XmlFieldExtractor`**

```java
// src/test/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractorTest.java
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

        // ReqAuthPay rules
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

        // ReqNoticePay rules
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
        assertThat(result.field("senderAccount")).isEqualTo("40702810820100004437");
        assertThat(result.field("amount")).isEqualTo("15787");
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
        assertThat(result.field("bankOperId")).isEqualTo("671920591533");
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
    }

    @Test
    void handlesFieldNotPresentInXml() throws IOException {
        byte[] xml = loadFixture("test-xml/req-auth-pay-b2c.xml");
        ExtractionResult result = extractor.extract(xml);

        // rcvTspId is not in the B2C fixture
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
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=XmlFieldExtractorTest -q`
Expected: FAIL — `XmlFieldExtractor` class does not exist

- [ ] **Step 4: Implement `XmlFieldExtractor`**

```java
// src/main/java/ru/tkbbank/sbprouter/extraction/XmlFieldExtractor.java
package ru.tkbbank.sbprouter.extraction;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.*;

@Component
public class XmlFieldExtractor {

    private static final XMLInputFactory XML_FACTORY = XMLInputFactory.newInstance();
    private static final String PAYMENT_ELEMENT = "Payment";
    private static final String DOCUMENT_ELEMENT = "Document";
    private static final String STAN_ATTR = "stan";
    private static final String PNAME_ID = "PNameID";
    private static final String PVALUE = "PValue";

    private final Map<String, SbpRouterProperties.ExtractionRuleSet> rules;

    public XmlFieldExtractor(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        this.rules = rules;
    }

    public ExtractionResult extract(byte[] xml) {
        try {
            XMLStreamReader reader = XML_FACTORY.createXMLStreamReader(new ByteArrayInputStream(xml));
            return doParse(reader);
        } catch (XMLStreamException e) {
            return new ExtractionResult(null, null, Map.of());
        }
    }

    private ExtractionResult doParse(XMLStreamReader reader) throws XMLStreamException {
        String correlationId = null;
        String requestType = null;
        List<FieldRule> activeRules = null;
        Map<String, String> extracted = new HashMap<>();

        // Track XML path for path-based rules
        Deque<String> pathStack = new ArrayDeque<>();
        // Track named block state: current parent element, last seen PNameID
        String currentParent = null;
        String lastPNameId = null;
        boolean inPNameId = false;
        boolean inPValue = false;
        // Track path-based element text
        boolean collectingPathText = false;
        String collectingForField = null;

        // Set of parent element names we care about (PayProfile, AdditionInfo)
        Set<String> parentElements = new HashSet<>();
        if (rules != null) {
            for (var ruleSet : rules.values()) {
                for (var rule : ruleSet.allFields()) {
                    if (rule.isNamedBlock()) {
                        parentElements.add(rule.getParent());
                    }
                }
            }
        }

        StringBuilder textBuffer = new StringBuilder();

        while (reader.hasNext()) {
            int event = reader.next();

            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    String localName = reader.getLocalName();
                    pathStack.push(localName);

                    // Capture correlationId from <Document stan="...">
                    if (DOCUMENT_ELEMENT.equals(localName)) {
                        correlationId = reader.getAttributeValue(null, STAN_ATTR);
                    }

                    // Detect request type: first child of <Payment>
                    if (requestType == null && pathStack.size() >= 2) {
                        Iterator<String> it = pathStack.iterator();
                        String current = it.next(); // top of stack = current element
                        if (it.hasNext()) {
                            String parent = it.next();
                            if (PAYMENT_ELEMENT.equals(parent)) {
                                requestType = current;
                                activeRules = rules != null && rules.containsKey(requestType)
                                        ? rules.get(requestType).allFields()
                                        : null;
                            }
                        }
                    }

                    // Track parent elements for named block rules
                    if (parentElements.contains(localName)) {
                        currentParent = localName;
                        lastPNameId = null;
                    }

                    if (currentParent != null) {
                        if (PNAME_ID.equals(localName)) {
                            inPNameId = true;
                            textBuffer.setLength(0);
                        } else if (PVALUE.equals(localName)) {
                            inPValue = true;
                            textBuffer.setLength(0);
                        }
                    }

                    // Check path-based rules
                    if (activeRules != null) {
                        String currentPath = buildPath(pathStack);
                        for (FieldRule rule : activeRules) {
                            if (!rule.isNamedBlock() && rule.getPath() != null
                                    && rule.getPath().equals(currentPath)) {
                                collectingPathText = true;
                                collectingForField = rule.getName();
                                textBuffer.setLength(0);
                            }
                        }
                    }
                }

                case XMLStreamConstants.CHARACTERS -> {
                    if (inPNameId || inPValue || collectingPathText) {
                        textBuffer.append(reader.getText());
                    }
                }

                case XMLStreamConstants.END_ELEMENT -> {
                    String localName = reader.getLocalName();

                    if (inPNameId && PNAME_ID.equals(localName)) {
                        lastPNameId = textBuffer.toString().trim();
                        inPNameId = false;
                    } else if (inPValue && PVALUE.equals(localName)) {
                        String value = textBuffer.toString().trim();
                        inPValue = false;

                        // Match against named block rules
                        if (activeRules != null && currentParent != null && lastPNameId != null) {
                            for (FieldRule rule : activeRules) {
                                if (rule.isNamedBlock()
                                        && rule.getParent().equals(currentParent)
                                        && rule.getKey().equals(lastPNameId)) {
                                    extracted.put(rule.getName(), value);
                                }
                            }
                        }
                    }

                    if (collectingPathText && collectingForField != null) {
                        String currentPath = buildPath(pathStack);
                        // Check if this end element matches a path rule
                        for (FieldRule rule : activeRules) {
                            if (!rule.isNamedBlock() && rule.getPath() != null
                                    && rule.getPath().equals(currentPath)
                                    && rule.getName().equals(collectingForField)) {
                                extracted.put(rule.getName(), textBuffer.toString().trim());
                                collectingPathText = false;
                                collectingForField = null;
                                break;
                            }
                        }
                    }

                    // Exit parent element
                    if (parentElements.contains(localName) && localName.equals(currentParent)) {
                        currentParent = null;
                        lastPNameId = null;
                    }

                    pathStack.pop();
                }
            }
        }

        return new ExtractionResult(requestType, correlationId, Map.copyOf(extracted));
    }

    private String buildPath(Deque<String> stack) {
        var sb = new StringBuilder();
        var list = new ArrayList<>(stack);
        Collections.reverse(list);
        for (String el : list) {
            sb.append('/').append(el);
        }
        return sb.toString();
    }
}
```

- [ ] **Step 5: Wire extractor bean via config**

Add a factory method to create the extractor from properties. Update the `@Component` to accept properties:

```java
// Replace the constructor in XmlFieldExtractor.java:
    public XmlFieldExtractor(SbpRouterProperties properties) {
        this.rules = properties.getExtractionRules();
    }

    // Keep the package-private constructor for tests:
    XmlFieldExtractor(Map<String, SbpRouterProperties.ExtractionRuleSet> rules) {
        this.rules = rules;
    }
```

- [ ] **Step 6: Run tests**

Run: `mvn test -pl . -Dtest=XmlFieldExtractorTest -q`
Expected: 6 tests PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/extraction/ src/test/java/ru/tkbbank/sbprouter/extraction/
git commit -m "feat: implement configurable StAX XML field extractor"
```

---

## Task 5: Terminal Detector

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/routing/TerminalOwner.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/routing/TerminalDetector.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/routing/TerminalDetectorTest.java`

- [ ] **Step 1: Create `TerminalOwner` enum**

```java
// src/main/java/ru/tkbbank/sbprouter/routing/TerminalOwner.java
package ru.tkbbank.sbprouter.routing;

public enum TerminalOwner {
    TKB_PAY,
    EXTERNAL
}
```

- [ ] **Step 2: Write failing tests**

```java
// src/test/java/ru/tkbbank/sbprouter/routing/TerminalDetectorTest.java
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

    @Test
    void c2bTerminalInList_returnsTkbPay() {
        var fields = Map.of("sbpOperType", "C2BQRD", "rcvTspId", "MB0000700543");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.TKB_PAY);
    }

    @Test
    void c2bTerminalNotInList_returnsExternal() {
        var fields = Map.of("sbpOperType", "C2BQRD", "rcvTspId", "MB9999999999");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.EXTERNAL);
    }

    @Test
    void b2cTerminalWithPayPrefix_returnsTkbPay() {
        var fields = Map.of("sbpOperType", "B2C", "terminalName", "PayTerminal01");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.TKB_PAY);
    }

    @Test
    void b2cTerminalWithoutPayPrefix_returnsExternal() {
        var fields = Map.of("sbpOperType", "B2C", "terminalName", "SB01133");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.EXTERNAL);
    }

    @Test
    void missingSbpOperType_returnsExternal() {
        var fields = Map.of("terminalName", "PayTerminal01");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.EXTERNAL);
    }

    @Test
    void unknownSbpOperType_returnsExternal() {
        var fields = Map.of("sbpOperType", "UNKNOWN", "terminalName", "PayTerminal01");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.EXTERNAL);
    }

    @Test
    void c2bMissingRcvTspId_returnsExternal() {
        var fields = Map.of("sbpOperType", "C2BQRD");
        assertThat(detector.detect(fields)).isEqualTo(TerminalOwner.EXTERNAL);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=TerminalDetectorTest -q`
Expected: FAIL — `TerminalDetector` class does not exist

- [ ] **Step 4: Implement `TerminalDetector`**

```java
// src/main/java/ru/tkbbank/sbprouter/routing/TerminalDetector.java
package ru.tkbbank.sbprouter.routing;

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

    public TerminalDetector(SbpRouterProperties properties) {
        this(properties.getTerminals());
    }

    // Package-private constructor for tests
    TerminalDetector(SbpRouterProperties.Terminals terminals) {
        this.tkbPayList = Set.copyOf(terminals.getTkbPayList());
        this.c2bFieldName = terminals.getC2bTerminal().getFieldName();
        this.b2cFieldName = terminals.getB2cTerminal().getFieldName();
        this.b2cPrefix = terminals.getB2cTerminal().getTkbPayPrefix();
    }

    public TerminalOwner detect(Map<String, String> fields) {
        String operType = fields.get("sbpOperType");
        if (operType == null) {
            return TerminalOwner.EXTERNAL;
        }

        if (isC2b(operType)) {
            String tspId = fields.get(c2bFieldName);
            if (tspId != null && tkbPayList.contains(tspId)) {
                return TerminalOwner.TKB_PAY;
            }
            return TerminalOwner.EXTERNAL;
        }

        if (isB2c(operType)) {
            String termName = fields.get(b2cFieldName);
            if (termName != null && termName.startsWith(b2cPrefix)) {
                return TerminalOwner.TKB_PAY;
            }
            return TerminalOwner.EXTERNAL;
        }

        return TerminalOwner.EXTERNAL;
    }

    private boolean isC2b(String operType) {
        return operType.toUpperCase().startsWith("C2B");
    }

    private boolean isB2c(String operType) {
        return operType.toUpperCase().startsWith("B2C");
    }
}
```

- [ ] **Step 5: Run tests**

Run: `mvn test -pl . -Dtest=TerminalDetectorTest -q`
Expected: 7 tests PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/routing/TerminalOwner.java src/main/java/ru/tkbbank/sbprouter/routing/TerminalDetector.java src/test/java/ru/tkbbank/sbprouter/routing/
git commit -m "feat: implement terminal ownership detection (C2B list, B2C prefix)"
```

---

## Task 6: Routing Decision Engine

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/routing/RouteDecision.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngine.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngineTest.java`

- [ ] **Step 1: Create `RouteDecision` record**

```java
// src/main/java/ru/tkbbank/sbprouter/routing/RouteDecision.java
package ru.tkbbank.sbprouter.routing;

public record RouteDecision(
        String upstreamName,   // "infosrv", "stub-verification", "stub-connector"
        TerminalOwner terminalOwner,
        String requestType
) {}
```

- [ ] **Step 2: Write failing tests**

```java
// src/test/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngineTest.java
package ru.tkbbank.sbprouter.routing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.ExtractionResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingDecisionEngineTest {

    @ParameterizedTest(name = "flagOff: {0} terminal={1} → infosrv")
    @CsvSource({
            "ReqAuthPay, TKB_PAY",
            "ReqAuthPay, EXTERNAL",
            "ReqNoticePay, TKB_PAY",
            "ReqNoticePay, EXTERNAL"
    })
    void whenFlagOff_allGoToInfosrv(String requestType, TerminalOwner owner) {
        var engine = buildEngine(false);
        var result = new ExtractionResult(requestType, "corr-1", Map.of("state", "0"));

        RouteDecision decision = engine.decide(result, owner);
        assertThat(decision.upstreamName()).isEqualTo("infosrv");
    }

    @Test
    void whenFlagOn_externalReqAuthPay_goesToInfosrv() {
        var engine = buildEngine(true);
        var result = new ExtractionResult("ReqAuthPay", "corr-1", Map.of());

        RouteDecision decision = engine.decide(result, TerminalOwner.EXTERNAL);
        assertThat(decision.upstreamName()).isEqualTo("infosrv");
    }

    @Test
    void whenFlagOn_tkbPayReqAuthPay_goesToStubVerification() {
        var engine = buildEngine(true);
        var result = new ExtractionResult("ReqAuthPay", "corr-1", Map.of());

        RouteDecision decision = engine.decide(result, TerminalOwner.TKB_PAY);
        assertThat(decision.upstreamName()).isEqualTo("stub-verification");
    }

    @Test
    void whenFlagOn_tkbPayReqNoticePayConfirm_goesToStubConnector() {
        var engine = buildEngine(true);
        var result = new ExtractionResult("ReqNoticePay", "corr-1", Map.of("state", "0"));

        RouteDecision decision = engine.decide(result, TerminalOwner.TKB_PAY);
        assertThat(decision.upstreamName()).isEqualTo("stub-connector");
    }

    @Test
    void whenFlagOn_tkbPayReqNoticePayCancel_goesToStubConnector() {
        var engine = buildEngine(true);
        var result = new ExtractionResult("ReqNoticePay", "corr-1", Map.of("state", "-1"));

        RouteDecision decision = engine.decide(result, TerminalOwner.TKB_PAY);
        assertThat(decision.upstreamName()).isEqualTo("stub-connector");
    }

    @Test
    void unknownRequestType_goesToInfosrv() {
        var engine = buildEngine(true);
        var result = new ExtractionResult(null, "corr-1", Map.of());

        RouteDecision decision = engine.decide(result, TerminalOwner.EXTERNAL);
        assertThat(decision.upstreamName()).isEqualTo("infosrv");
    }

    @Test
    void decisionContainsMetadata() {
        var engine = buildEngine(true);
        var result = new ExtractionResult("ReqAuthPay", "corr-1", Map.of());

        RouteDecision decision = engine.decide(result, TerminalOwner.TKB_PAY);
        assertThat(decision.terminalOwner()).isEqualTo(TerminalOwner.TKB_PAY);
        assertThat(decision.requestType()).isEqualTo("ReqAuthPay");
    }

    private RoutingDecisionEngine buildEngine(boolean tkbPayEnabled) {
        var routing = new SbpRouterProperties.Routing();
        routing.setTkbPayEnabled(tkbPayEnabled);
        return new RoutingDecisionEngine(routing);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=RoutingDecisionEngineTest -q`
Expected: FAIL — `RoutingDecisionEngine` class does not exist

- [ ] **Step 4: Implement `RoutingDecisionEngine`**

```java
// src/main/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngine.java
package ru.tkbbank.sbprouter.routing;

import org.springframework.stereotype.Component;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;
import ru.tkbbank.sbprouter.extraction.ExtractionResult;

@Component
public class RoutingDecisionEngine {

    private static final String INFOSRV = "infosrv";
    private static final String STUB_VERIFICATION = "stub-verification";
    private static final String STUB_CONNECTOR = "stub-connector";

    private final SbpRouterProperties.Routing routing;

    public RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        this.routing = routing;
    }

    public RouteDecision decide(ExtractionResult extraction, TerminalOwner terminalOwner) {
        String requestType = extraction.requestType();

        // Unknown request type or flag OFF or external terminal → InfoSRV
        if (requestType == null || !routing.isTkbPayEnabled() || terminalOwner == TerminalOwner.EXTERNAL) {
            return new RouteDecision(INFOSRV, terminalOwner, requestType);
        }

        // Flag ON + TKB_PAY terminal
        String upstream = switch (requestType) {
            case "ReqAuthPay" -> STUB_VERIFICATION;
            case "ReqNoticePay" -> STUB_CONNECTOR;
            default -> INFOSRV;
        };

        return new RouteDecision(upstream, terminalOwner, requestType);
    }
}
```

- [ ] **Step 5: Wire via properties — add factory bean**

The `RoutingDecisionEngine` constructor accepts `SbpRouterProperties.Routing`. Add a second constructor accepting `SbpRouterProperties`:

```java
Replace the constructor to support both Spring injection and test usage:

```java
    public RoutingDecisionEngine(SbpRouterProperties properties) {
        this.routing = properties.getRouting();
    }

    // Package-private constructor for tests
    RoutingDecisionEngine(SbpRouterProperties.Routing routing) {
        this.routing = routing;
    }
```

- [ ] **Step 6: Run tests**

Run: `mvn test -pl . -Dtest=RoutingDecisionEngineTest -q`
Expected: 7 tests PASS (4 parameterized + 3 regular)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/routing/ src/test/java/ru/tkbbank/sbprouter/routing/RoutingDecisionEngineTest.java
git commit -m "feat: implement routing decision engine with feature-flag"
```

---

## Task 7: Error Response Builder

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/proxy/ErrorResponseBuilder.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/proxy/ErrorResponseBuilderTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/ru/tkbbank/sbprouter/proxy/ErrorResponseBuilderTest.java
package ru.tkbbank.sbprouter.proxy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseBuilderTest {

    private final ErrorResponseBuilder builder = new ErrorResponseBuilder();

    @Test
    void buildsAnsAuthPayError() {
        String xml = builder.buildErrorResponse("ReqAuthPay", "Connection refused");

        assertThat(xml).contains("<AnsAuthPay>");
        assertThat(xml).contains("<Code>-1</Code>");
        assertThat(xml).contains("<LogMsg>Connection refused</LogMsg>");
        assertThat(xml).contains("</AnsAuthPay>");
        assertThat(xml).startsWith("<?xml");
    }

    @Test
    void buildsAnsNoticePayError() {
        String xml = builder.buildErrorResponse("ReqNoticePay", "Timeout");

        assertThat(xml).contains("<AnsNoticePay>");
        assertThat(xml).doesNotContain("<AnsAuthPay>");
        assertThat(xml).contains("<BankOperId></BankOperId>");
    }

    @Test
    void unknownRequestType_buildsAnsAuthPayByDefault() {
        String xml = builder.buildErrorResponse(null, "Unknown error");

        assertThat(xml).contains("<AnsAuthPay>");
        assertThat(xml).contains("<Code>-1</Code>");
    }

    @Test
    void escapesXmlSpecialCharsInMessage() {
        String xml = builder.buildErrorResponse("ReqAuthPay", "Error <&> \"test\"");

        assertThat(xml).contains("&lt;&amp;&gt;");
        assertThat(xml).doesNotContain("<&>");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=ErrorResponseBuilderTest -q`
Expected: FAIL

- [ ] **Step 3: Implement `ErrorResponseBuilder`**

```java
// src/main/java/ru/tkbbank/sbprouter/proxy/ErrorResponseBuilder.java
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
        if ("ReqNoticePay".equals(requestType)) {
            return ANS_NOTICE_PAY_TEMPLATE.formatted(escaped);
        }
        return ANS_AUTH_PAY_TEMPLATE.formatted(escaped);
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl . -Dtest=ErrorResponseBuilderTest -q`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/proxy/ErrorResponseBuilder.java src/test/java/ru/tkbbank/sbprouter/proxy/
git commit -m "feat: implement GCSvc XML error response builder"
```

---

## Task 8: Stub Handlers

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/stub/StubHandler.java`
- Create: `src/test/java/ru/tkbbank/sbprouter/stub/StubHandlerTest.java`

- [ ] **Step 1: Write failing tests**

```java
// src/test/java/ru/tkbbank/sbprouter/stub/StubHandlerTest.java
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
        String response = webClient.post()
                .uri("/stub/verification")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue("<test/>")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).contains("<AnsAuthPay>");
        assertThat(response).contains("<Code>0</Code>");
    }

    @Test
    void stubConnector_returnsAnsNoticePayWithBankOperId() {
        String response = webClient.post()
                .uri("/stub/connector")
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue("<test/>")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(response).contains("<AnsNoticePay>");
        assertThat(response).contains("<BankOperId>STUB-");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -pl . -Dtest=StubHandlerTest -q`
Expected: FAIL — no route for `/stub/verification`

- [ ] **Step 3: Implement `StubHandler`**

```java
// src/main/java/ru/tkbbank/sbprouter/stub/StubHandler.java
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
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(ANS_AUTH_PAY_OK);
    }

    private Mono<ServerResponse> handleConnector(ServerRequest request) {
        String body = ANS_NOTICE_PAY_TEMPLATE.formatted(System.currentTimeMillis());
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(body);
    }
}
```

- [ ] **Step 4: Run tests**

Run: `mvn test -pl . -Dtest=StubHandlerTest -q`
Expected: 2 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/stub/ src/test/java/ru/tkbbank/sbprouter/stub/
git commit -m "feat: add stub handlers for Verification and Connector modules"
```

---

## Task 9: Proxy Client and WebClient Config

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/config/WebClientConfig.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/proxy/ProxyClient.java`

- [ ] **Step 1: Create `WebClientConfig`**

```java
// src/main/java/ru/tkbbank/sbprouter/config/WebClientConfig.java
package ru.tkbbank.sbprouter.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient proxyWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(30));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
```

- [ ] **Step 2: Create `ProxyClient`**

```java
// src/main/java/ru/tkbbank/sbprouter/proxy/ProxyClient.java
package ru.tkbbank.sbprouter.proxy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import ru.tkbbank.sbprouter.config.SbpRouterProperties;

import java.time.Duration;
import java.util.Map;

@Component
public class ProxyClient {

    private static final Logger log = LoggerFactory.getLogger(ProxyClient.class);

    private final WebClient webClient;
    private final Map<String, SbpRouterProperties.UpstreamConfig> upstreams;

    public ProxyClient(WebClient proxyWebClient, SbpRouterProperties properties) {
        this.webClient = proxyWebClient;
        this.upstreams = properties.getUpstreams();
    }

    public Mono<byte[]> forward(String upstreamName, byte[] body, Map<String, String> extraHeaders) {
        SbpRouterProperties.UpstreamConfig config = upstreams.get(upstreamName);
        if (config == null) {
            return Mono.error(new IllegalArgumentException("Unknown upstream: " + upstreamName));
        }

        Duration timeout = config.getTimeout() != null ? config.getTimeout() : Duration.ofSeconds(30);
        int maxAttempts = config.getRetry() != null ? config.getRetry().getMaxAttempts() : 1;
        Duration backoff = config.getRetry() != null ? config.getRetry().getBackoff() : Duration.ofMillis(500);

        var request = webClient.post()
                .uri(config.getUrl())
                .contentType(MediaType.APPLICATION_XML);

        // Add extra headers
        for (var entry : extraHeaders.entrySet()) {
            request = request.header("X-Sbp-" + entry.getKey(), entry.getValue());
        }

        return request
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxAttempts, backoff)
                        .filter(ex -> !(ex instanceof java.util.concurrent.TimeoutException))
                        .doBeforeRetry(signal ->
                                log.warn("Retrying upstream={} attempt={}", upstreamName, signal.totalRetries() + 1)))
                .doOnError(ex -> log.error("Upstream {} failed: {}", upstreamName, ex.getMessage()));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/config/WebClientConfig.java src/main/java/ru/tkbbank/sbprouter/proxy/ProxyClient.java
git commit -m "feat: add WebClient config and proxy client"
```

---

## Task 10: Metrics Service

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/observability/MetricsService.java`

- [ ] **Step 1: Implement `MetricsService`**

```java
// src/main/java/ru/tkbbank/sbprouter/observability/MetricsService.java
package ru.tkbbank.sbprouter.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MetricsService {

    private final MeterRegistry registry;
    private final AtomicInteger activeRequests;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
        this.activeRequests = registry.gauge("sbp_router_active_requests", new AtomicInteger(0));
    }

    public void incrementActiveRequests() {
        activeRequests.incrementAndGet();
    }

    public void decrementActiveRequests() {
        activeRequests.decrementAndGet();
    }

    public void recordRequest(String requestType, String terminalOwner, String routeDecision) {
        Counter.builder("sbp_router_requests_total")
                .tag("requestType", safe(requestType))
                .tag("terminalOwner", safe(terminalOwner))
                .tag("routeDecision", safe(routeDecision))
                .register(registry)
                .increment();
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void stopTimer(Timer.Sample sample, String requestType, String routeDecision) {
        sample.stop(Timer.builder("sbp_router_request_duration_seconds")
                .tag("requestType", safe(requestType))
                .tag("routeDecision", safe(routeDecision))
                .register(registry));
    }

    public void recordUpstreamError(String requestType, String upstream, String errorType) {
        Counter.builder("sbp_router_upstream_errors_total")
                .tag("requestType", safe(requestType))
                .tag("upstream", safe(upstream))
                .tag("errorType", safe(errorType))
                .register(registry)
                .increment();
    }

    private String safe(String value) {
        return value != null ? value : "unknown";
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/observability/
git commit -m "feat: add Micrometer metrics service"
```

---

## Task 11: Main GcsvcHandler — Wiring Everything Together

**Files:**
- Create: `src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java`
- Create: `src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcRouter.java`

- [ ] **Step 1: Create `GcsvcHandler`**

```java
// src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java
package ru.tkbbank.sbprouter.proxy;

import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import ru.tkbbank.sbprouter.extraction.ExtractionResult;
import ru.tkbbank.sbprouter.extraction.XmlFieldExtractor;
import ru.tkbbank.sbprouter.observability.MetricsService;
import ru.tkbbank.sbprouter.routing.RouteDecision;
import ru.tkbbank.sbprouter.routing.RoutingDecisionEngine;
import ru.tkbbank.sbprouter.routing.TerminalDetector;
import ru.tkbbank.sbprouter.routing.TerminalOwner;

import java.util.HashMap;
import java.util.Map;

import static net.logstash.logback.argument.StructuredArguments.kv;

@Component
public class GcsvcHandler {

    private static final Logger log = LoggerFactory.getLogger(GcsvcHandler.class);

    private final XmlFieldExtractor extractor;
    private final TerminalDetector terminalDetector;
    private final RoutingDecisionEngine routingEngine;
    private final ProxyClient proxyClient;
    private final ErrorResponseBuilder errorResponseBuilder;
    private final MetricsService metrics;

    public GcsvcHandler(XmlFieldExtractor extractor,
                        TerminalDetector terminalDetector,
                        RoutingDecisionEngine routingEngine,
                        ProxyClient proxyClient,
                        ErrorResponseBuilder errorResponseBuilder,
                        MetricsService metrics) {
        this.extractor = extractor;
        this.terminalDetector = terminalDetector;
        this.routingEngine = routingEngine;
        this.proxyClient = proxyClient;
        this.errorResponseBuilder = errorResponseBuilder;
        this.metrics = metrics;
    }

    public Mono<ServerResponse> handle(ServerRequest request) {
        Timer.Sample timerSample = metrics.startTimer();
        metrics.incrementActiveRequests();

        return request.bodyToMono(byte[].class)
                .flatMap(body -> {
                    // 1. Parse XML
                    ExtractionResult extraction;
                    try {
                        extraction = extractor.extract(body);
                    } catch (Exception e) {
                        log.error("Failed to parse XML", e);
                        metrics.decrementActiveRequests();
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_XML)
                                .bodyValue(errorResponseBuilder.buildErrorResponse(null, "Invalid XML: " + e.getMessage()));
                    }

                    // 2. Determine terminal ownership
                    TerminalOwner owner = terminalDetector.detect(extraction.fields());

                    // 3. Make routing decision
                    RouteDecision decision = routingEngine.decide(extraction, owner);

                    // 4. Log
                    log.info("Routing request",
                            kv("correlationId", extraction.correlationId()),
                            kv("requestType", extraction.requestType()),
                            kv("terminalOwner", owner.name()),
                            kv("routeDecision", decision.upstreamName()));

                    // 5. Record metrics
                    metrics.recordRequest(
                            extraction.requestType(),
                            owner.name(),
                            decision.upstreamName());

                    // 6. Build extra headers from extra-fields
                    Map<String, String> extraHeaders = new HashMap<>(extraction.fields());

                    // 7. Proxy
                    return proxyClient.forward(decision.upstreamName(), body, extraHeaders)
                            .flatMap(responseBody -> {
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
                                metrics.decrementActiveRequests();
                                return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_XML)
                                        .bodyValue(responseBody);
                            })
                            .onErrorResume(ex -> {
                                log.error("Upstream error",
                                        kv("correlationId", extraction.correlationId()),
                                        kv("upstream", decision.upstreamName()),
                                        kv("error", ex.getMessage()));
                                metrics.recordUpstreamError(
                                        extraction.requestType(),
                                        decision.upstreamName(),
                                        ex.getClass().getSimpleName());
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
                                metrics.decrementActiveRequests();
                                String errorXml = errorResponseBuilder.buildErrorResponse(
                                        extraction.requestType(), ex.getMessage());
                                return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_XML)
                                        .bodyValue(errorXml);
                            });
                });
    }
}
```

- [ ] **Step 2: Create `GcsvcRouter`**

```java
// src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcRouter.java
package ru.tkbbank.sbprouter.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.accept;

@Configuration
public class GcsvcRouter {

    @Bean
    public RouterFunction<ServerResponse> gcsvcRoute(GcsvcHandler handler) {
        return RouterFunctions.route(
                POST("/api/gcsvc").and(accept(MediaType.APPLICATION_XML)),
                handler::handle);
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcHandler.java src/main/java/ru/tkbbank/sbprouter/proxy/GcsvcRouter.java
git commit -m "feat: implement main GcsvcHandler wiring all components"
```

---

## Task 12: Structured Logging Configuration

**Files:**
- Create: `src/main/resources/logback-spring.xml`

- [ ] **Step 1: Create Logback config**

```xml
<!-- src/main/resources/logback-spring.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProfile name="!test">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <timestampPattern>yyyy-MM-dd'T'HH:mm:ss.SSS'Z'</timestampPattern>
                <fieldNames>
                    <timestamp>timestamp</timestamp>
                    <version>[ignore]</version>
                    <levelValue>[ignore]</levelValue>
                </fieldNames>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>

    <springProfile name="test">
        <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
            <encoder>
                <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

- [ ] **Step 2: Update `application.yml` with full config**

Add the full extraction rules and upstream config to `src/main/resources/application.yml`:

```yaml
# src/main/resources/application.yml
server:
  port: 8080
  shutdown: graceful

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: sbp-router

sbp-router:
  extraction-rules:
    ReqAuthPay:
      routing-fields:
        - name: terminalName
          parent: PayProfile
          key: Tran.TermName
        - name: rcvTspId
          parent: PayProfile
          key: RcvTSPId
        - name: sbpOperation
          parent: PayProfile
          key: SbpOperation
        - name: sbpOperType
          parent: PayProfile
          key: SbpOperType
      extra-fields:
        - name: senderAccount
          parent: PayProfile
          key: SndAccountNum
        - name: amount
          path: /Document/GCSvc/Payment/ReqAuthPay/Funds/Amount
    ReqNoticePay:
      routing-fields:
        - name: terminalName
          parent: AdditionInfo
          key: Tran.TermName
        - name: rcvTspId
          parent: AdditionInfo
          key: RcvTSPId
        - name: sbpOperation
          parent: AdditionInfo
          key: SbpOperation
        - name: state
          path: /Document/GCSvc/Payment/ReqNoticePay/State
      extra-fields:
        - name: bankOperId
          path: /Document/GCSvc/Payment/ReqNoticePay/BankOperId

  terminals:
    c2b-terminal:
      field-name: rcvTspId
    b2c-terminal:
      field-name: terminalName
      tkb-pay-prefix: "Pay"
    tkb-pay-list:
      - MB0000700543
      - MB0000004185

  routing:
    tkb-pay-enabled: false

  upstreams:
    infosrv:
      url: http://infosrv.bank.local/api/gcsvc
      timeout: 30s
      retry:
        max-attempts: 2
        backoff: 500ms
    stub-verification:
      url: http://localhost:${server.port}/stub/verification
    stub-connector:
      url: http://localhost:${server.port}/stub/connector
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/logback-spring.xml src/main/resources/application.yml
git commit -m "feat: add structured JSON logging and full application config"
```

---

## Task 13: Integration Tests

**Files:**
- Create: `src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerIntegrationTest.java`

- [ ] **Step 1: Write integration tests with WireMock**

```java
// src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerIntegrationTest.java
package ru.tkbbank.sbprouter.proxy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        Assertions.assertNotNull(body);
        Assertions.assertTrue(body.contains("<Code>0</Code>"));

        // Verify InfoSRV was called
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
                .bodyValue(requestXml)
                .exchange()
                .expectStatus().isOk();

        // Verify extra headers were forwarded
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
```

- [ ] **Step 2: Run integration tests**

Run: `mvn test -pl . -Dtest=GcsvcHandlerIntegrationTest -q`
Expected: 5 tests PASS

- [ ] **Step 3: Run all tests**

Run: `mvn test -q`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add src/test/java/ru/tkbbank/sbprouter/proxy/GcsvcHandlerIntegrationTest.java
git commit -m "test: add integration tests with WireMock for full request flow"
```

---

## Task 14: Final Verification

- [ ] **Step 1: Run full build**

Run: `mvn clean verify -q`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 2: Start the application**

Run: `mvn spring-boot:run`
Expected: Application starts on port 8080

- [ ] **Step 3: Verify health endpoint**

Run: `curl -s http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 4: Verify prometheus endpoint**

Run: `curl -s http://localhost:8080/actuator/prometheus | head -5`
Expected: Prometheus metrics output

- [ ] **Step 5: Stop the application and commit**

```bash
git add -A
git commit -m "feat: complete SBP proxy router v0.1.0"
```
