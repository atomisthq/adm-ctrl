# docker buildx create --name=wooden-pickle
# docker buildx build --push -t vonwig/pkcs-create --builder=wooden-pickle -f docker/Dockerfile .

#syntax=docker/dockerfile:1.4
FROM babashka/babashka:1.2.174@sha256:3f27de35b84d524654ed2e73d8c4fd998a15a7f5d364931c2390059922195f88 AS bb

FROM eclipse-temurin:19.0.2_7-jre-jammy@sha256:2ef86f03626f0df508565a24faf251d095a8c0e3a4ece31b21c71798f465a29f

RUN <<EOF
apt-get update
apt-get -y --no-install-recommends install openssl curl
apt-get clean
rm -rf /var/lib/apt/lists/*
EOF

USER nobody
WORKDIR /app

COPY --from=bb /usr/local/bin/bb /usr/local/bin
COPY certs.clj /app

ENTRYPOINT ["bb","-f","certs.clj"]
