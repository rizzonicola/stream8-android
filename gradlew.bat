@rem Launcher minimale del Gradle Wrapper (vedi gradlew per i dettagli).
@rem Se manca gradle\wrapper\gradle-wrapper.jar esegui:  gradle wrapper --gradle-version 8.10.2
@echo off
setlocal
set APP_HOME=%~dp0
set WRAPPER_JAR=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
if not exist "%WRAPPER_JAR%" (
  echo gradle-wrapper.jar mancante. Esegui una volta: gradle wrapper --gradle-version 8.10.2 1>&2
  exit /b 1
)
if defined JAVA_HOME (set JAVACMD=%JAVA_HOME%\bin\java.exe) else (set JAVACMD=java.exe)
"%JAVACMD%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=gradlew" -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
endlocal
