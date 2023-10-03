# docker buildx create --name=wooden-pickle
# docker buildx build --push -t vonwig/adm-ctrl:latest --builder=wooden-pickle --platform=linux/arm64 .

FROM clojure:tools-deps-jammy@sha256:0f76020267f987fcf8f8e5d4e04211eb4c0ffcc035fb6d69a043a6bd74cdd7a9 AS build

WORKDIR /build

COPY deps.edn .
COPY build.clj .
COPY ./resources ./resources
COPY ./src ./src

RUN clj -T:build uber

FROM ghcr.io/graalvm/graalvm-ce:22.3.0@sha256:29cdd9790c3e1bc63d6eda505f0df786c680fae074f8e169202034f31329a669 AS native
WORKDIR /native-build
RUN gu install native-image

COPY --from=build /build/app.jar /native-build/
RUN native-image --verbose -jar /native-build/app.jar -H:Name=adm-ctrl

FROM eclipse-temurin:19-jre@sha256:e7934eaf29182fa4f883e4cc28a127fdd70e652f8208d3c8dc4edc3bfc92fb49 AS jvm-server

WORKDIR /app

COPY --from=build /build/app.jar .

CMD ["java", "-jar", "app.jar"]

FROM alpine:3.17@sha256:f71a5f071694a785e064f05fed657bf8277f1b2113a8ed70c90ad486d6ee54dc
COPY --from=native /native-build/adm-ctrl /
ENTRYPOINT ["/adm-ctrl"]

