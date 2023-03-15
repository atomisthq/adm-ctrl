# docker buildx create --name=wooden-pickle
# docker buildx build --push -t vonwig/adm-ctrl:latest --builder=wooden-pickle --platform=linux/arm64,linux/amd64 .

FROM --platform=linux/arm64 clojure:tools-deps-jammy@sha256:7cb4b2ca76cd184fb05c1aa0aefd7e10331261da60969fc9a6dc87da8b375832 AS build

WORKDIR /app

COPY deps.edn .
COPY build.clj .
COPY ./src ./src

RUN clj -T:build uber

FROM eclipse-temurin:19-jre@sha256:6dd1793be096a1b88bd1c07c99d2d2341968409144a99c5b061460e60f9d1788

WORKDIR /app

COPY --from=build /app/app.jar .

CMD ["java", "-jar", "app.jar"]
