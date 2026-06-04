# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Copy all POMs for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY proto-shared/pom.xml proto-shared/
COPY user-service/pom.xml user-service/
COPY catalog-service/pom.xml catalog-service/
COPY order-service/pom.xml order-service/
COPY file-service/pom.xml file-service/
COPY payment-service/pom.xml payment-service/

# proto-shared sources needed to generate stubs before payment-service can compile
COPY proto-shared/src/ proto-shared/src/

# 1) Install root POM so child POMs can resolve their parent from local repo
# 2) Install proto-shared (payment-service depends on its generated stubs)
# 3) Pre-resolve payment-service external dependencies (cached until pom changes)
RUN chmod +x mvnw && \
    ./mvnw -N install --no-transfer-progress -q && \
    ./mvnw -pl proto-shared clean install -DskipTests --no-transfer-progress -q && \
    ./mvnw -pl payment-service dependency:resolve dependency:resolve-plugins \
        --no-transfer-progress -q

# Copy payment-service sources and build
COPY payment-service/src/ payment-service/src/
RUN ./mvnw -pl payment-service clean package -DskipTests --no-transfer-progress

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
COPY --from=builder /build/payment-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
