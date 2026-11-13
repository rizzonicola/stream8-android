#!/bin/sh
# Launcher minimale del Gradle Wrapper.
# Serve il file gradle/wrapper/gradle-wrapper.jar, che NON è incluso in questo archivio
# (generato offline). Per ottenerlo, con Gradle installato:   gradle wrapper --gradle-version 8.10.2
# Il comando rigenera anche gradlew e gradlew.bat ufficiali, sostituendo questi.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "gradle-wrapper.jar mancante. Esegui una volta: gradle wrapper --gradle-version 8.10.2" >&2
  exit 1
fi

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD=java
fi

exec "$JAVACMD" -Xmx64m -Xms64m $JAVA_OPTS $GRADLE_OPTS \
  "-Dorg.gradle.appname=gradlew" \
  -classpath "$WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain "$@"
