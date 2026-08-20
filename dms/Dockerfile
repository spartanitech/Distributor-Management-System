# ---------- Stage 1: build the jar with Maven ----------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only pom.xml first so Docker can cache the dependency download layer
COPY pom.xml .
RUN mvn -B dependency:go-offline

# Now copy the actual source and build
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---------- Stage 2: small runtime image ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Non-root user (don't run the app as root inside the container)
RUN groupadd -r dms && useradd -r -g dms dms

COPY --from=build /build/target/*.jar app.jar

# uploads/ is where FileUploadUtil (paymentproofs, products, companylogo, ...)
# writes files -- must be a mounted volume or every redeploy wipes it.
RUN mkdir -p /app/uploads && chown -R dms:dms /app

USER dms

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
