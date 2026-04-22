@echo off
REM UTF-8 console + JVM UTF-8 (Logback outputs UTF-8; default CMD is CP936 -> mojibake without chcp 65001)
chcp 65001 >nul
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot"
set "ROOT=%~dp0.."
set "JAR=%ROOT%\WxFbsir-engine\target\wxfbsir-engine-1.3.1.jar"
if not exist "%JAR%" (
  echo JAR not found: %JAR%
  exit /b 1
)
cd /d "%ROOT%\WxFbsir-engine"
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 -jar "%JAR%"
