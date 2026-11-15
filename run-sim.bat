@echo off
rem Starts the FTC simulator. Arguments are passed through, for example:
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController"
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController" --robot robots\tank.json
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController" --project2 "C:\path\to\OtherFtcRobotController"
rem Or put project=... and robot=... in sim.properties and just run .\run-sim.bat
setlocal
cd /d "%~dp0"

rem Find a JDK 17+ : JAVA_HOME, then java on PATH, then the newest JDK in %USERPROFILE%\.jdks
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" goto run
where javac >nul 2>nul && goto run
for /f "delims=" %%d in ('dir /b /ad /o-n "%USERPROFILE%\.jdks\jdk-*" 2^>nul') do (
    if exist "%USERPROFILE%\.jdks\%%d\bin\javac.exe" (
        set "JAVA_HOME=%USERPROFILE%\.jdks\%%d"
        goto run
    )
)
echo No JDK found. Install JDK 17 or newer (for example Eclipse Temurin 21) or set JAVA_HOME.
exit /b 1

:run
call "%~dp0gradlew.bat" -q :engine:installDist || exit /b 1
call "%~dp0engine\build\install\ftc-sim\bin\ftc-sim.bat" %*
