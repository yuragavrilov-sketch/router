package ru.tkbbank.sbprouter.proxy;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RequestPredicates.contentType;

@Configuration
public class GcsvcRouter {
    @Bean
    public RouterFunction<ServerResponse> gcsvcRoute(GcsvcHandler handler) {
        return RouterFunctions.route(
                POST("/api/gcsvc").and(contentType(MediaType.APPLICATION_XML)),
                handler::handle);
    }
}
