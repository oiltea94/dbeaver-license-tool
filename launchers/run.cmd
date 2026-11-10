@echo off
setlocal DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "JAVA_EXE="
if defined JAVA_CMD set "JAVA_EXE=%JAVA_CMD%"
if defined JAVA_EXE goto found
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined DBEAVER_HOME if exist "%DBEAVER_HOME%\jre\bin\java.exe" set "JAVA_EXE=%DBEAVER_HOME%\jre\bin\java.exe"
if not defined JAVA_EXE if defined DBEAVER_HOME if exist "%DBEAVER_HOME%\jre\Contents\Home\bin\java.exe" set "JAVA_EXE=%DBEAVER_HOME%\jre\Contents\Home\bin\java.exe"
if not defined JAVA_EXE if defined DBEAVER_HOME if exist "%DBEAVER_HOME%\Contents\Eclipse\jre\Contents\Home\bin\java.exe" set "JAVA_EXE=%DBEAVER_HOME%\Contents\Eclipse\jre\Contents\Home\bin\java.exe"
if not defined JAVA_EXE if defined DBEAVER_HOME if exist "%DBEAVER_HOME%\Contents\Eclipse\jre\bin\java.exe" set "JAVA_EXE=%DBEAVER_HOME%\Contents\Eclipse\jre\bin\java.exe"
if not defined JAVA_EXE if exist "%SCRIPT_DIR%..\jre\bin\java.exe" set "JAVA_EXE=%SCRIPT_DIR%..\jre\bin\java.exe"
if not defined JAVA_EXE if exist "%SCRIPT_DIR%jre\bin\java.exe" set "JAVA_EXE=%SCRIPT_DIR%jre\bin\java.exe"
if defined JAVA_EXE goto found
for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
if not defined JAVA_EXE (
    echo Java 21+ required. Set JAVA_HOME, DBEAVER_HOME, or JAVA_CMD. 1>&2
    exit /b 1
)
:found
set "JAVA_MAJOR="
for /f "tokens=2" %%V in ('""%JAVA_EXE%" --version 2^>nul"') do if not defined JAVA_MAJOR set "JAVA_MAJOR=%%V"
if not defined JAVA_MAJOR goto bad_java
for /f "tokens=1 delims=.-+" %%V in ("%JAVA_MAJOR%") do set "JAVA_MAJOR=%%V"
for /f "delims=0123456789" %%V in ("%JAVA_MAJOR%") do goto bad_java
if %JAVA_MAJOR% LSS 21 (
    echo Java 21+ required; detected Java %JAVA_MAJOR%. 1>&2
    exit /b 1
)
if not exist "%SCRIPT_DIR%dbeaver-license-tool.jar" (
    echo Missing dbeaver-license-tool.jar. Extract all package files together. 1>&2
    exit /b 1
)
"%JAVA_EXE%" -jar "%SCRIPT_DIR%dbeaver-license-tool.jar" %*
exit /b %errorlevel%
:bad_java
echo Java 21+ required. Failed to determine version of "%JAVA_EXE%". 1>&2
exit /b 1
