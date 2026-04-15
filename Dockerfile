# Dockerfile for Clojure AI Agent
# Multi-stage build for optimized production image

# Stage 1: Build stage
FROM clojure:temurin-21-alpine AS builder

WORKDIR /app

# Copy project files
COPY deps.edn ./
COPY src ./src
COPY resources ./resources

# Download dependencies
RUN clojure -P

# Create uberjar
RUN clojure -T:uberjar uberjar :jar '"target/clj-agent.jar"'

# Stage 2: Runtime stage
FROM eclipse-temurin:21-jre-alpine

# Install required system dependencies
RUN apk add --no-cache \
    bash \
    curl \
    tini

# Create non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy uberjar from builder stage
COPY --from=builder /app/target/clj-agent.jar ./clj-agent.jar

# Copy configuration
COPY config ./config
COPY resources ./resources

# Set permissions
RUN chown -R appuser:appgroup /app
USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Expose port
EXPOSE 8080

# Use tini as init process for proper signal handling
ENTRYPOINT ["/sbin/tini", "--"]

# Default command
CMD ["java", "-jar", "clj-agent.jar"]

# Labels
LABEL org.opencontainers.image.title="Clojure AI Agent"
LABEL org.opencontainers.image.description="AI Agent system built with Clojure"
LABEL org.opencontainers.image.version="1.0.0"
LABEL org.opencontainers.image.authors="Clojure AI Agent Team"
LABEL org.opencontainers.image.licenses="MIT"
