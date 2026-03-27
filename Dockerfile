FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Orhestra"
LABEL description="Orhestra Coordinator — headless HTTP server"

WORKDIR /app

# Copy the fat JAR (built by maven-shade-plugin)
COPY target/OrhestraV2-*.jar app.jar

# Copy parameter schema (served at GET /api/v1/parameter-schema)
COPY src/main/resources/parameters.json /app/parameters.json

# Data directory for H2 database file
RUN mkdir -p /app/data

# Expose the default HTTP port
EXPOSE 8081

# Environment variables with defaults (override at runtime)
ENV ORHESTRA_PORT=8081
ENV ORHESTRA_DB_URL=jdbc:h2:file:/app/data/orhestra;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_UPPER=FALSE

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
