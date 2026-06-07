# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY proto-catalog/pom.xml proto-catalog/
COPY proto-payment/pom.xml proto-payment/
COPY user-service/pom.xml user-service/
COPY catalog-service/pom.xml catalog-service/
COPY order-service/pom.xml order-service/
COPY file-service/pom.xml file-service/
COPY payment-service/pom.xml payment-service/

# order-service depends on both proto stubs
COPY proto-catalog/src/ proto-catalog/src/
COPY proto-payment/src/ proto-payment/src/

RUN chmod +x mvnw && \
    ./mvnw -N install --no-transfer-progress -q && \
    ./mvnw -pl proto-catalog clean install -DskipTests --no-transfer-progress -q && \
    ./mvnw -pl proto-payment clean install -DskipTests --no-transfer-progress -q && \
    ./mvnw -pl order-service dependency:resolve dependency:resolve-plugins \
        --no-transfer-progress -q

COPY order-service/src/ order-service/src/
RUN ./mvnw -pl order-service clean package -DskipTests --no-transfer-progress

# OTel agent stage
FROM eclipse-temurin:25-jdk AS otel-agent
ARG OTEL_AGENT_VERSION=2.7.0
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /otel \
    && curl -fsSL -o /otel/javaagent.jar \
       "https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar"

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=otel-agent /otel/javaagent.jar /otel/javaagent.jar
COPY --from=builder /build/order-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
