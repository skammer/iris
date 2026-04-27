FROM clojure:temurin-21-alpine AS builder

WORKDIR /app

COPY deps.edn ./
COPY src ./src
COPY resources ./resources
COPY build.clj ./

RUN clojure -P
RUN clojure -T:uberjar uberjar :jar '"target/iris.jar"'

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache \
    bash \
    curl \
    tini

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/target/iris.jar ./iris.jar

COPY config ./config
COPY resources ./resources

ENV AGENT_API_HOST=0.0.0.0 \
    AGENT_API_PORT=8080 \
    AGENT_SQLITE_PATH=/app/data/agent.db \
    JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED

RUN mkdir -p /app/data /app/logs && chown -R appuser:appgroup /app
USER appuser

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

EXPOSE 8080
VOLUME ["/app/data"]

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java", "-jar", "iris.jar", "serve"]

# Labels
LABEL org.opencontainers.image.title="Iris"
LABEL org.opencontainers.image.description="Isolated Reasoning & Intelligence Substrate built with Clojure"
LABEL org.opencontainers.image.version="0.1.0"
LABEL org.opencontainers.image.authors="Iris Team"
LABEL org.opencontainers.image.licenses="MIT"
