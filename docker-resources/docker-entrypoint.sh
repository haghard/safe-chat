#!/bin/bash

set -e
set -x

#-XX:MaxRAMFraction=1 \
#-XX:+HeapDumpOnOutOfMemoryError \
#-XX:HeapDumpPath=/data/logs/    \
#-XX:+UnlockExperimentalVMOptions \
#-XX:+PrintCommandLineFlags \

# Selecting GC
# Unless your application has rather strict pause-time requirements, first run your application and allow the VM to select a collector.
#
# If necessary, adjust the heap size to improve performance. If the performance still doesn't meet your goals, then use the following guidelines as a starting point for selecting a collector:
# If the application has a small data set (up to approximately 100 MB), then select the serial collector with the option -XX:+UseSerialGC.
# If the application will be run on a single processor and there are no pause-time requirements, then select the serial collector with the option -XX:+UseSerialGC.
# If (a) peak application performance is the first priority and (b) there are no pause-time requirements or pauses of one second or longer are acceptable, then let the VM select the collector or select the parallel collector with -XX:+UseParallelGC.
# If response time is more important than overall throughput and garbage collection pauses must be kept shorter, then select the mostly concurrent collector with -XX:+UseG1GC.
# If response time is a high priority, and/or you are using a very large heap, then select a fully concurrent collector with -XX:UseZGC.

APP_OPTS="-server \
          -XshowSettings \
          -XX:+UseStringDeduplication \
          -XX:+UseG1GC \
          -XX:+UnlockExperimentalVMOptions \
          -XX:InitialRAMPercentage=75 \
          -XX:MaxRAMPercentage=75 \
          -XX:+PreferContainerQuotaForCPUCount \
          -DENV=${ENV} \
          -DHTTP_PORT=${HTTP_PORT} \
          -DCONFIG=${CONFIG} \
          -DCONTACT_POINTS=${CONTACT_POINTS} \
          -Dakka.remote.artery.canonical.hostname=${HOSTNAME} \
          -Dakka.remote.artery.canonical.port=${AKKA_PORT} \
          -DCASSANDRA=${CASSANDRA} \
          -DCAS_USER=${CAS_USER} \
          -DCAS_PWS="${CAS_PWS}

java ${APP_OPTS} -cp ${APP_BASE}/conf -jar ${APP_BASE}/safe-chat-${VERSION}.jar