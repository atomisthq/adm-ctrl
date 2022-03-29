FROM babashka/babashka:0.6.5@sha256:66abc2ad36c33bd82537b11e341629a0ead7c119f301c1b8c7865cfc45e61ddd AS bb

FROM openjdk:11-jre-slim-buster@sha256:f8f8da9dd2d9b158d5475177764c37bce94d1da4e05dadd1104ea14bd8e7672a

RUN apt-get update && \
    apt-get -y --no-install-recommends install openssl curl && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*
# RUN apk add --update openssl && \
    # rm -rf /var/cache/apk/*

USER nobody
WORKDIR /app

COPY --from=bb /usr/local/bin/bb /usr/local/bin
COPY certs.clj /app

ENTRYPOINT ["bb","-f","certs.clj"]
