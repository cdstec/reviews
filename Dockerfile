FROM openjdk:8-jdk-alpine

RUN mkdir deploy
COPY build/libs/*.jar /deploy/app.jar
RUN chmod +x deploy/app.jar

ARG service_version
ARG enable_ratings
ARG star_color
ENV SERVICE_VERSION ${service_version:-v1}
ENV ENABLE_RATINGS ${enable_ratings:-false}
ENV STAR_COLOR ${star_color:-black}

ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","/deploy/app.jar","&"]