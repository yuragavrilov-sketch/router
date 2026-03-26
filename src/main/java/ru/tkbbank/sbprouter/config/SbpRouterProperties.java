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
