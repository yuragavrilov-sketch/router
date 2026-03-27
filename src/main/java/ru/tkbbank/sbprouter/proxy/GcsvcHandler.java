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
                    ExtractionResult extraction;
                    try {
                        extraction = extractor.extract(body);
                    } catch (Exception e) {
                        log.error("Failed to parse XML", e);
                        return ServerResponse.badRequest()
                                .contentType(MediaType.APPLICATION_XML)
                                .bodyValue(errorResponseBuilder.buildErrorResponse(null, "Invalid XML: " + e.getMessage()));
                    }

                    TerminalOwner owner = terminalDetector.detect(extraction.fields());
                    RouteDecision decision = routingEngine.decide(extraction, owner);

                    log.info("Routing request",
                            kv("correlationId", extraction.correlationId()),
                            kv("requestType", extraction.requestType()),
                            kv("terminalOwner", owner.name()),
                            kv("routeDecision", decision.upstreamName()));

                    metrics.recordRequest(extraction.requestType(), owner.name(), decision.upstreamName());

                    Map<String, String> extraHeaders = new HashMap<>(extraction.extraFields());

                    return proxyClient.forward(decision.upstreamName(), body, extraHeaders)
                            .flatMap(responseBody -> {
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
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
                                        extraction.requestType(), decision.upstreamName(), ex.getClass().getSimpleName());
                                metrics.stopTimer(timerSample, extraction.requestType(), decision.upstreamName());
                                String errorXml = errorResponseBuilder.buildErrorResponse(
                                        extraction.requestType(), ex.getMessage());
                                return ServerResponse.ok()
                                        .contentType(MediaType.APPLICATION_XML)
                                        .bodyValue(errorXml);
                            });
                })
                .doFinally(signal -> metrics.decrementActiveRequests());
    }
}
