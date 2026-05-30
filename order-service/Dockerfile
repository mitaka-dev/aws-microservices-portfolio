# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY proto-shared/pom.xml proto-shared/
COPY user-service/pom.xml user-service/
COPY catalog-service/pom.xml catalog-service/
COPY order-service/pom.xml order-service/

COPY proto-shared/src/ proto-shared/src/

RUN chmod +x mvnw && \
    ./mvnw -N install --no-transfer-progress -q && \
    ./mvnw -pl proto-shared clean install -DskipTests --no-transfer-progress -q && \
    ./mvnw -pl order-service dependency:resolve dependency:resolve-plugins \
        --no-transfer-progress -q

COPY order-service/src/ order-service/src/
RUN ./mvnw -pl order-service clean package -DskipTests --no-transfer-progress

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=builder /build/order-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
