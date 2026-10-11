# NetVerify application image (API + worker roles via runtime args/env).
# C binaries are built on Ubuntu 22.04 (Jammy) to match the Temurin JRE runtime glibc.

# -----------------------------------------------------------------------------
# Stage 1: build net_server / net_client via Makefile
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS c-builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends gcc make libc6-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY Makefile ./
COPY networking/ networking/
RUN make

# -----------------------------------------------------------------------------
# Stage 2: build Spring Boot fat JAR (tests run on the host, not in image builds)
# -----------------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS java-builder

WORKDIR /build
COPY control-plane/pom.xml ./
COPY control-plane/src ./src
RUN mvn -B -DskipTests package \
    && mv target/control-plane-*.jar /build/app.jar

# -----------------------------------------------------------------------------
# Stage 3: runtime — JRE 21 + python3 + worker filesystem layout
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 netverify \
    && useradd --system --uid 10001 --gid netverify --home-dir /opt/netverify \
       --shell /usr/sbin/nologin --create-home netverify

WORKDIR /opt/netverify

COPY --from=c-builder --chown=netverify:netverify /build/net_server /opt/netverify/net_server
COPY --from=c-builder --chown=netverify:netverify /build/net_client /opt/netverify/net_client
COPY --from=java-builder --chown=netverify:netverify /build/app.jar /opt/netverify/app.jar
COPY --chown=netverify:netverify automation/ /opt/netverify/automation/

USER netverify:netverify

# Role (api/worker) and Spring args are supplied at runtime (e.g. Compose in M2).
ENTRYPOINT ["java", "-jar", "/opt/netverify/app.jar"]
