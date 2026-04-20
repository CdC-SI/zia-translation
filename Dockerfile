FROM docker-commons.zas.admin.ch/zas/imagebase/application/java:21-openjdk-headless-ubi-2.7.0
COPY target/zia-translation.jar /app/
CMD java \
    -XX:+UseG1GC \
    -XX:+ExplicitGCInvokesConcurrent \
    -XX:MaxGCPauseMillis=500 \
    -XX:ParallelGCThreads=2 \
    -Xms256M \
    -Xmx2048M \
    -XX:MinHeapFreeRatio=10 \
    -XX:MaxHeapFreeRatio=20 \
    -XX:GCTimeRatio=4 \
    -XX:AdaptiveSizePolicyWeight=90 \
    -jar /app/zia-translation.jar
