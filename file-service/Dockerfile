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

# 1) Install root POM so child POMs can resolve their parent from local repo
# 2) Pre-resolve file-service external dependencies (cached until pom changes)
RUN chmod +x mvnw && \
    ./mvnw -N install --no-transfer-progress -q && \
    ./mvnw -pl file-service dependency:resolve dependency:resolve-plugins \
        --no-transfer-progress -q

# Copy file-service sources and build
COPY file-service/src/ file-service/src/
RUN ./mvnw -pl file-service clean package -DskipTests --no-transfer-progress

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=builder /build/file-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
