# syntax=docker/dockerfile:1
# SkyCore skyblock server: Paper 26.3 (build 253) + SkyCore + pinned plugins. Java 25.

# ---- 1. build the SkyCore plugin -------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY skycore/ ./
RUN chmod +x gradlew && ./gradlew build --no-daemon -q

# ---- 2. runtime -----------------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/skycore
COPY paper-26.3-253.jar ./paper-26.3-253.jar
COPY plugins.lock ./plugins.lock
COPY scripts/ ./scripts/
COPY server/ ./server/
COPY docker/entrypoint.sh ./entrypoint.sh
COPY --from=build /src/build/libs/SkyCore.jar ./skycore/build/libs/SkyCore.jar
RUN chmod +x scripts/*.sh entrypoint.sh && scripts/fetch-plugins.sh /opt/skycore/build/plugins /opt/skycore/plugins.lock

ENV MEMORY=4G \
    TZ=Europe/Lisbon \
    EULA=false
VOLUME ["/data"]
EXPOSE 25565
ENTRYPOINT ["/opt/skycore/entrypoint.sh"]
