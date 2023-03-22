# docker buildx create --name=wooden-pickle
# docker buildx build --push -t vonwig/adm-ctrl:latest --builder=wooden-pickle --platform=linux/arm64 .

FROM clojure:tools-deps-jammy@sha256:d89ec4073ac29856b56f1d47687094f85fe5b9b553861439cce9991a2df7247f AS build

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

FROM alpine:3.17@sha256:ff6bdca1701f3a8a67e328815ff2346b0e4067d32ec36b7992c1fdc001dc8517
COPY --from=native /native-build/adm-ctrl /
ENTRYPOINT ["/adm-ctrl"]

