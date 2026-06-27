# Stage 1: Build stage
FROM gradle:8-jdk17 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle installDist --no-daemon

# Stage 2: Runtime stage
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache bash
RUN mkdir /app
WORKDIR /app
COPY --from=build /home/gradle/src/build/install/dwd-radar-proxy /app
EXPOSE 8080
CMD ["./bin/dwd-radar-proxy"]
