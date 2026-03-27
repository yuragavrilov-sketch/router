# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Import corporate CA certificates into OS trust store and Java truststore
COPY TCB-Root-CA.crt TCB-ENT-CA.crt /tmp/certs/
RUN cp /tmp/certs/*.crt /usr/local/share/ca-certificates/ && \
    update-ca-certificates && \
    CACERTS=$(find $JAVA_HOME -name cacerts -type f 2>/dev/null | head -1) && \
    echo "Found cacerts at: $CACERTS" && \
    keytool -importcert -noprompt -trustcacerts \
      -alias tcb-root-ca -file /tmp/certs/TCB-Root-CA.crt \
      -keystore "$CACERTS" -storepass changeit && \
    keytool -importcert -noprompt -trustcacerts \
      -alias tcb-ent-ca -file /tmp/certs/TCB-ENT-CA.crt \
      -keystore "$CACERTS" -storepass changeit

COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=build /app/target/sbp-router-*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
