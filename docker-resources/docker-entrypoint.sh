#!/bin/bash

set -e
set -x

#-XX:MaxRAMFraction=1 \
#-XX:+HeapDumpOnOutOfMemoryError \
#-XX:HeapDumpPath=/data/logs/    \


APP_OPTS="-server \
          -XX:MaxGCPauseMillis=400 \
          -XX:+UseStringDeduplication \
          -XX:+UseG1GC \
          -XX:ConcGCThreads=4 -XX:ParallelGCThreads=4 \
          -XX:+UseContainerSupport \
          -XX:+PreferContainerQuotaForCPUCount \
          -XX:+UnlockExperimentalVMOptions \
          -XX:InitialRAMPercentage=60 \
          -XX:MaxRAMPercentage=75 \
          -XX:MinRAMPercentage=50 \
          -XshowSettings \
          -DENV=${ENV} \
          -DHTTP_PORT=${HTTP_PORT} \
          -DCONFIG=${CONFIG} \
          -DSEEDS=${SEEDS} \
          -DHOSTNAME=${HOSTNAME} \
          -DAKKA_PORT=${AKKA_PORT} \
          -DDISCOVERY_METHOD=${DISCOVERY_METHOD} \
          -Dcassandra.hosts=${CASSANDRA} \
          -Dcassandra.user=${CAS_USER} \
          -Dcassandra.psw="${CAS_PWS}

java ${APP_OPTS} -cp ${APP_BASE}/conf -jar ${APP_BASE}/safe-chat-${VERSION}.jar