@echo off
rem Copy this file to local-config.cmd and edit only values that differ locally.
rem local-config.cmd is ignored by Git and may contain local secrets.

rem Optional explicit locations for non-standard installations.
rem set "JAVA17_HOME=C:\path\to\jdk-17"
rem set "MYSQL_HOME=C:\path\to\mysql"
rem set "MYSQL_SERVICE_NAME=MySQL"

rem Local MySQL connection used both by the startup check and Spring Boot.
set "RA_DATABASE_NAME=research_assistant"
set "SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/research_assistant?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8"
set "SPRING_DATASOURCE_USERNAME=root"
set "SPRING_DATASOURCE_PASSWORD="

rem Generate a stable random value once; do not commit the resulting file.
rem PowerShell example:
rem $b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b); [Convert]::ToBase64String($b)
rem set "RA_MASTER_KEY=paste-the-generated-value-here"
