@echo off
REM Maven wrapper for Research Assistant (PowerShell / CMD)
REM Usage: .\mvnw.cmd [maven-args...]

setlocal

if "%JAVA_HOME%"=="" (
  echo Error: JAVA_HOME is not set. Please set JAVA_HOME to your JDK installation.
  exit /b 1
)

if not exist "%JAVA_HOME%\bin\java.exe" (
  echo Error: JAVA_HOME is set to '%JAVA_HOME%' but '%JAVA_HOME%\bin\java.exe' does not exist.
  exit /b 1
)

set "WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar"

if not exist "%WRAPPER_JAR%" (
  echo Error: wrapper jar not found at %WRAPPER_JAR%
  exit /b 1
)

"%JAVA_HOME%\bin\java" -cp "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%~dp0." org.apache.maven.wrapper.MavenWrapperMain %*
