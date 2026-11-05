@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-21.0.13+9"
set "PATH=%JAVA_HOME%\bin;%PATH%"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Java 21 not found: %JAVA_HOME%
    pause
    exit /b 1
)
call "%~dp0gradlew.bat" "-Dorg.gradle.java.home=%JAVA_HOME%" runClient %*
set "RUN_EXIT_CODE=%ERRORLEVEL%"
if not "%RUN_EXIT_CODE%"=="0" (
    echo.
    echo Launch failed. See the error above.
    pause
)
exit /b %RUN_EXIT_CODE%
