FROM eclipse-temurin:17-jdk
RUN apt-get update && apt-get install -y --no-install-recommends unzip wget && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android
RUN mkdir -p $ANDROID_HOME/cmdline-tools && cd /tmp \
 && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q commandlinetools-linux-*.zip && mv cmdline-tools $ANDROID_HOME/cmdline-tools/latest && rm -f *.zip
RUN yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses > /dev/null \
 && $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" > /dev/null \
 && chmod -R a+rwX $ANDROID_HOME
RUN wget -q https://services.gradle.org/distributions/gradle-8.7-bin.zip -O /tmp/g.zip && unzip -q /tmp/g.zip -d /opt && rm /tmp/g.zip
ENV PATH=/opt/gradle-8.7/bin:$PATH
