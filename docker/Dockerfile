FROM babashka/babashka:0.8.157@sha256:9dcba7fc59cb24454141a5c8631a53942d8226a3c5ff8ce07b0e2b70b7891d0a AS bb

FROM eclipse-temurin:19.0.2_7-jdk-jammy@sha256:f4bfb5d45d098b39cecaba9d39cdd1c9200a98787dc97ec4d6194fc2fbc6c427

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
