# Build stage
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /build

# Copy Maven wrapper and all pom files first for dependency layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY proto-shared/pom.xml proto-shared/
COPY user-service/pom.xml user-service/
COPY catalog-service/pom.xml catalog-service/
COPY order-service/pom.xml order-service/
COPY file-service/pom.xml file-service/

# Download dependencies (this layer is cached until a pom.xml changes)
RUN chmod +x mvnw && ./mvnw -pl user-service -am dependency:resolve dependency:resolve-plugins \
    --no-transfer-progress -q

# Copy source and build
COPY proto-shared/src/ proto-shared/src/
COPY user-service/src/ user-service/src/
RUN ./mvnw -pl user-service -am clean package -DskipTests --no-transfer-progress

# Runtime stage
FROM eclipse-temurin:25-jre AS runtime
RUN addgroup --system app && adduser --system --ingroup app app
WORKDIR /app
COPY --from=builder /build/user-service/target/*-exec.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
