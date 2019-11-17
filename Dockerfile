FROM maven:3-jdk-8-alpine
RUN apk --no-cache add curl

ENV JAVA_OPTS="-Xms8G -Xmx8G"

WORKDIR /opt/opentripplanner
COPY pom.xml ./pom.xml

RUN mvn -DskipTests -Dgpg.skip install

COPY src ./src
COPY .git ./.git

RUN mvn --offline -Dmaven.test.skip=true package

# ---

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
