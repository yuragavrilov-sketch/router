package ru.tkbbank.sbprouter.observability;

import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

@Component
@Order(-1)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();

        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        log.info("[INCOMING] {} {} contentType={} remoteAddr={}",
                req.getMethod(), req.getURI(),
                req.getHeaders().getContentType(),
                req.getRemoteAddress());

        if (path.startsWith("/api")) {
            return filterWithBodyLogging(exchange, chain, req);
        }

        return chain.filter(exchange)
                .doOnSuccess(v -> logResponse(exchange, req))
                .doOnError(ex -> log.error("[ERROR] {} {} — {}",
                        req.getMethod(), req.getURI(), ex.getMessage()));
    }

    private Mono<Void> filterWithBodyLogging(ServerWebExchange exchange,
                                             WebFilterChain chain,
                                             ServerHttpRequest req) {
        ServerHttpRequestDecorator loggingRequest = new ServerHttpRequestDecorator(req) {
            @Override
            public Flux<DataBuffer> getBody() {
                return super.getBody().doOnNext(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    dataBuffer.readPosition(dataBuffer.readPosition() - bytes.length);
                    log.info("[REQUEST BODY] {} {} body={}",
                            req.getMethod(), req.getURI(),
                            new String(bytes, StandardCharsets.UTF_8));
                });
            }
        };

        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator loggingResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            DataBufferUtils.release(dataBuffer);
                            log.info("[RESPONSE BODY] {} {} status={} body={}",
                                    req.getMethod(), req.getURI(),
                                    getStatusCode() != null ? getStatusCode().value() : 0,
                                    new String(bytes, StandardCharsets.UTF_8));
                            return super.writeWith(Mono.just(bufferFactory.wrap(bytes)));
                        });
            }
        };

        ServerWebExchange decoratedExchange = exchange.mutate()
                .request(loggingRequest)
                .response(loggingResponse)
                .build();

        return chain.filter(decoratedExchange)
                .doOnError(ex -> log.error("[ERROR] {} {} — {}",
                        req.getMethod(), req.getURI(), ex.getMessage()));
    }

    private void logResponse(ServerWebExchange exchange, ServerHttpRequest req) {
        int status = exchange.getResponse().getStatusCode() != null
                ? exchange.getResponse().getStatusCode().value() : 0;
        if (status == 404) {
            log.warn("[NO ROUTE] {} {} — no handler matched, returned 404",
                    req.getMethod(), req.getURI());
        }
        log.info("[RESPONSE] {} {} → status={}",
                req.getMethod(), req.getURI(), status);
    }
}
