# =========================================================================
# HAVITA SAAS — BACKEND MULTI-STAGE DOCKERFILE (JAVA 21 & GRADLE 8)
# =========================================================================

# Stage 1: Build & Package
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copiar configuración de dependencias
COPY gradlew gradlew.bat build.gradle settings.gradle ./
COPY gradle ./gradle

# Descargar dependencias en caché
RUN ./gradlew dependencies --no-daemon || true

# Copiar código fuente y compilar artefacto jar optimizado
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# Stage 2: Production Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Crear usuario sin privilegios para seguridad en producción
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copiar el artefacto generado
COPY --from=builder --chown=appuser:appgroup /workspace/build/libs/*.jar app.jar

USER appuser:appgroup

# Parámetros JVM optimizados para contenedores
ENV JAVA_OPTS="-XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=75.0 -Duser.timezone=America/Bogota"
ENV PORT=9094

EXPOSE 9094

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -q --spider http://localhost:9094/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
