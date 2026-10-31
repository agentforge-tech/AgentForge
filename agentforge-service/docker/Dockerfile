# 1) Build the Spring Boot service (SQLite is the default runtime database).
FROM maven:3.9-eclipse-temurin-17 AS backend
WORKDIR /workspace
COPY . .
RUN printf '%s\n' \
    '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
    '  <mirrors>' \
    '    <mirror><id>aliyun-public</id><name>Aliyun Maven Public</name><url>https://maven.aliyun.com/repository/public</url><mirrorOf>*</mirrorOf></mirror>' \
    '  </mirrors>' \
    '</settings>' > /tmp/maven-settings.xml \
    && mvn -s /tmp/maven-settings.xml -B -q -pl agentforge-service/agentforge-service-web -am \
    -Dmaven.test.skip=true package

# 2) Build the Vue frontend.
FROM node:20-alpine AS frontend
WORKDIR /workspace/ui
COPY agentforge-service/agentforge-service-ui/package.json \
     agentforge-service/agentforge-service-ui/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY agentforge-service/agentforge-service-ui/ ./
RUN npm run build

# 3) Runtime: service + SQLite in one image, UI hosted by the backend.
FROM eclipse-temurin:17-jre-jammy AS runtime

ENV APP_HOME=/opt/agentforge \
    TZ=Asia/Shanghai \
    AGENTFORGE_DB_TYPE=sqlite \
    SERVER_PORT=8080 \
    JAVA_OPTS="-Xms256m -Xmx512m"

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl sqlite3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p ${APP_HOME}/bin ${APP_HOME}/conf ${APP_HOME}/data \
       ${APP_HOME}/logs ${APP_HOME}/upload ${APP_HOME}/lib \
       ${APP_HOME}/sql/sqllite

COPY agentforge-service/docker/container/bin/ ${APP_HOME}/bin/
COPY agentforge-service/bin/local/upgrate-sqlite.sh ${APP_HOME}/bin/upgrade-sql.sh
COPY agentforge-service/conf/application.yml.example ${APP_HOME}/conf/application.yml
COPY agentforge-service/conf/logback.xml ${APP_HOME}/conf/logback.xml
COPY agentforge-service/sql/sqllite/ ${APP_HOME}/sql/sqllite/
COPY --from=backend \
     /workspace/agentforge-service/agentforge-service-web/target/agentforge-service-web-*.jar \
     ${APP_HOME}/lib/app.jar
COPY --from=frontend /workspace/ui/dist/ ${APP_HOME}/conf/static/

RUN chmod +x ${APP_HOME}/bin/*.sh

WORKDIR ${APP_HOME}
EXPOSE 8080
ENTRYPOINT ["/opt/agentforge/bin/start.sh"]
