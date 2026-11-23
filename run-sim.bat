@echo off
rem Starts the FTC simulator. Arguments are passed through, for example:
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController"
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController" --robot robots\tank.json
rem   .\run-sim.bat --project "C:\path\to\FtcRobotController" --project2 "C:\path\to\OtherFtcRobotController"
rem Or put project=... and robot=... in sim.properties and just run .\run-sim.bat
setlocal
rem A relative --project is relative to where this was started, not to the simulator's folder.
set "FTCSIM_CALLER_DIR=%CD%"
cd /d "%~dp0"

rem A JDK 17 or newer: JAVA_HOME, then the javac on PATH, then the newest by version of the JDKs in
rem USERPROFILE\.jdks and Android Studio's own (jbr). Folder names don't tell the version
rem (temurin-11 sorts after corretto-21), so each JDK's release file does. JAVA_HOME is always set
rem to the one chosen: Gradle and the simulator's start script run its java.
if not defined JAVA_HOME goto pathjavac
set "JAVA_HOME=%JAVA_HOME:"=%"
call :jdkversion "%JAVA_HOME%"
if %JDK_MAJOR% GEQ 17 goto found
echo JAVA_HOME (%JAVA_HOME%) is not a JDK 17 or newer; looking for one. 1>&2
set "JAVA_HOME="

:pathjavac
rem The javac on PATH says where its JDK is (what is on PATH may be a link to it, as Oracle's
rem javapath is).
where javac >nul 2>nul || goto search
set "PATH_JDK="
for /f "tokens=1,2,*" %%a in ('javac -J-XshowSettings:properties -version 2^>^&1') do if "%%a"=="java.home" set "PATH_JDK=%%c"
if not defined PATH_JDK goto search
call :jdkversion "%PATH_JDK%"
if %JDK_MAJOR% LSS 17 goto search
set "JAVA_HOME=%PATH_JDK%"
goto found

:search
set "BEST="
set "BEST_MAJOR=0"
for /f "delims=" %%d in ('dir /b /ad "%USERPROFILE%\.jdks" 2^>nul') do call :consider "%USERPROFILE%\.jdks\%%d"
if defined FTCSIM_ANDROID_STUDIO_JBR goto jbrlist
call :consider "%ProgramFiles%\Android\Android Studio\jbr"
call :consider "%LOCALAPPDATA%\Programs\Android Studio\jbr"
call :consider "%LOCALAPPDATA%\Android\Android Studio\jbr"
goto chosen
:jbrlist
rem (for the tests: Android Studio JDK folders, separated by semicolons, instead of the usual places)
for %%j in ("%FTCSIM_ANDROID_STUDIO_JBR:;=" "%") do call :consider "%%~j"
:chosen
if defined BEST goto best
echo No JDK 17 or newer found. Install one (for example Eclipse Temurin 21) or set JAVA_HOME. 1>&2
exit /b 1
:best
set "JAVA_HOME=%BEST%"

:found
rem (for the tests: the JDK chosen, without building or starting anything)
if not defined FTCSIM_PRINT_JAVA_HOME goto run
echo %JAVA_HOME%
exit /b 0

:run
rem Builds the simulator and copies it to build\run\ID, a folder no later build rewrites under it.
call "%~dp0gradlew.bat" -q :engine:prepareRun || exit /b 1
set "FTCSIM_RUN="
if exist "%~dp0build\run\current" set /p FTCSIM_RUN=<"%~dp0build\run\current"
if not defined FTCSIM_RUN goto norun
if not exist "%~dp0build\run\%FTCSIM_RUN%\bin\ftc-sim.bat" goto norun
call "%~dp0build\run\%FTCSIM_RUN%\bin\ftc-sim.bat" %*
exit /b %ERRORLEVEL%
:norun
echo The simulator's build is not in build\run; run .\run-sim.bat again. 1>&2
exit /b 1

rem Sets JDK_MAJOR to the major Java version (8 for 1.8) of the JDK in the folder given, from its
rem release file: 0 when it isn't a JDK (a JRE has no bin\javac.exe) or the file doesn't say.
:jdkversion
set "JDK_MAJOR=0"
set "JDK_VERSION="
if not exist "%~1\bin\javac.exe" exit /b 0
if not exist "%~1\release" exit /b 0
for /f "usebackq tokens=1,* delims==" %%a in ("%~1\release") do if "%%a"=="JAVA_VERSION" set "JDK_VERSION=%%~b"
if not defined JDK_VERSION exit /b 0
for /f "tokens=1,2 delims=._+-" %%x in ("%JDK_VERSION%") do if "%%x"=="1" (set "JDK_MAJOR=%%y") else set "JDK_MAJOR=%%x"
rem Anything but digits: unknown.
if not defined JDK_MAJOR set "JDK_MAJOR=0"
for /f "delims=0123456789" %%n in ("%JDK_MAJOR%") do set "JDK_MAJOR=0"
exit /b 0

rem Keeps the folder given in BEST when it is a JDK 17 or newer and at least as new as BEST.
:consider
call :jdkversion "%~1"
if %JDK_MAJOR% LSS 17 exit /b 0
if %JDK_MAJOR% LSS %BEST_MAJOR% exit /b 0
set "BEST=%~1"
set "BEST_MAJOR=%JDK_MAJOR%"
exit /b 0
