# syntax=docker/dockerfile:1
ARG BASE_IMAGE=registry.access.redhat.com/ubi9/openjdk-25-runtime:1.24@sha256:2ddacad5332f4e481353668f210623f7e7420f1561515f5686e5abd33cd6d38f
FROM ${BASE_IMAGE}
ARG VERSION=0.0.1-SNAPSHOT
ARG REVISION=unknown
ARG CREATED=unknown
LABEL org.opencontainers.image.title="Tiket" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.created="${CREATED}" \
      org.opencontainers.image.source="https://github.com/Wiesmak/tiket"
WORKDIR /opt/tiket
COPY --chmod=0444 tiket-backend/build/libs/*-boot.jar /opt/tiket/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.io.tmpdir=/tmp -Duser.home=/tmp"
USER 185
EXPOSE 9090 9091
STOPSIGNAL SIGTERM
ENTRYPOINT ["java","-jar","/opt/tiket/app.jar"]
