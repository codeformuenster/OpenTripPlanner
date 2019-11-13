# FROM maven:3-jdk-8
# RUN apt-get update && apt-get -y install curl

FROM maven:3-jdk-8-alpine
RUN apk --no-cache add curl 

WORKDIR /opt/opentripplanner
COPY pom.xml ./pom.xml
COPY src ./src
COPY .git ./.git

RUN mvn package
# Total time: ~15 min

# ---

# FROM openjdk:8u121-jre-alpine
FROM openjdk:8-jre-alpine
RUN apk --no-cache add curl bash ttf-dejavu

WORKDIR /opt/opentripplanner
COPY --from=0 /opt/opentripplanner/target/*-shaded.jar ./otp-shaded.jar

ENV JAVA_OPTS="-Xms8G -Xmx8G"

EXPOSE 8080
CMD ["java", \
        "-Duser.timezone=Europe/Berlin", \
        "-jar", "/opt/opentripplanner/otp-shaded.jar", \
        "--server", \
        "--basePath", "./", \
        "--graphs", "./graphs", \
        "--router", "cfm"]