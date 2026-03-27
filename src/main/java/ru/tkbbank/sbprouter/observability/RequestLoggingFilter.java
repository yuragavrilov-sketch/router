package ru.tkbbank.sbprouter.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(-1)
public class RequestLoggingFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();

        log.info("[INCOMING] {} {} contentType={} remoteAddr={}",
                req.getMethod(), req.getURI(),
                req.getHeaders().getContentType(),
                req.getRemoteAddress());

        return chain.filter(exchange)
                .doOnSuccess(v -> {
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value() : 0;
                    if (status == 404) {
                        log.warn("[NO ROUTE] {} {} — no handler matched, returned 404",
                                req.getMethod(), req.getURI());
                    }
                    log.info("[RESPONSE] {} {} → status={}",
                            req.getMethod(), req.getURI(), status);
                })
                .doOnError(ex -> log.error("[ERROR] {} {} — {}",
                        req.getMethod(), req.getURI(), ex.getMessage()));
    }
}
