# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Copy all POMs for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY proto-shared/pom.xml proto-shared/
COPY user-service/pom.xml user-service/
COPY catalog-service/pom.xml catalog-service/

# proto-shared sources needed to generate stubs before catalog-service can compile
COPY proto-shared/src/ proto-shared/src/

# 1) Install root POM so child POMs can resolve their parent from local repo
# 2) Install proto-shared (catalog-service depends on its generated stubs)
# 3) Pre-resolve catalog-service external dependencies (cached until pom changes)
RUN chmod +x mvnw && \
    ./mvnw -N install --no-transfer-progress -q && \
    ./mvnw -pl proto-shared clean install -DskipTests --no-transfer-progress -q && \
    ./mvnw -pl catalog-service dependency:resolve dependency:resolve-plugins \
        --no-transfer-progress -q

# Copy catalog-service sources and build
COPY catalog-service/src/ catalog-service/src/
RUN ./mvnw -pl catalog-service clean package -DskipTests --no-transfer-progress

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=builder /build/catalog-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
