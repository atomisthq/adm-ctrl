FROM babashka/babashka:0.6.5@sha256:66abc2ad36c33bd82537b11e341629a0ead7c119f301c1b8c7865cfc45e61ddd AS bb

FROM openjdk:11-jre-slim-buster@sha256:3394135c413b0e6dcbc5283b16051e3f3c88855c79642fc0621aea81f5388c43

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
