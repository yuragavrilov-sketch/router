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

        var request = webClient.post().uri(config.getUrl()).contentType(MediaType.APPLICATION_XML);
        for (var entry : extraHeaders.entrySet()) {
            request = request.header("X-Sbp-" + entry.getKey(), entry.getValue());
        }
        return request.bodyValue(body).retrieve().bodyToMono(byte[].class)
                .timeout(timeout)
                .retryWhen(Retry.backoff(maxAttempts, backoff)
                        .filter(ex -> !(ex instanceof java.util.concurrent.TimeoutException))
                        .doBeforeRetry(signal -> log.warn("Retrying upstream={} attempt={}", upstreamName, signal.totalRetries() + 1)))
                .doOnError(ex -> log.error("Upstream {} failed: {}", upstreamName, ex.getMessage()));
    }
}
