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

    public void incrementActiveRequests() { activeRequests.incrementAndGet(); }
    public void decrementActiveRequests() { activeRequests.decrementAndGet(); }

    public void recordRequest(String requestType, String terminalOwner, String routeDecision) {
        Counter.builder("sbp_router_requests_total")
                .tag("requestType", safe(requestType)).tag("terminalOwner", safe(terminalOwner))
                .tag("routeDecision", safe(routeDecision))
                .register(registry).increment();
    }

    public Timer.Sample startTimer() { return Timer.start(registry); }

    public void stopTimer(Timer.Sample sample, String requestType, String routeDecision) {
        sample.stop(Timer.builder("sbp_router_request_duration_seconds")
                .tag("requestType", safe(requestType)).tag("routeDecision", safe(routeDecision))
                .register(registry));
    }

    public void recordUpstreamError(String requestType, String upstream, String errorType) {
        Counter.builder("sbp_router_upstream_errors_total")
                .tag("requestType", safe(requestType)).tag("upstream", safe(upstream))
                .tag("errorType", safe(errorType))
                .register(registry).increment();
    }

    private String safe(String value) { return value != null ? value : "unknown"; }
}
