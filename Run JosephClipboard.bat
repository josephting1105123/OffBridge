@echo off
set "JAVA_EXE=C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
if not exist "%JAVA_EXE%" (
    echo "Using System Java"
    set "JAVA_EXE=java"
)

rem Resolve the built Compose uber-jar under the redirected build2/ dir (name carries the
rem version, so glob it instead of hardcoding — survives version bumps).
set "JAR="
for %%f in ("Window Ver\build2\compose\jars\*.jar") do set "JAR=%%f"
if not defined JAR (
    echo Could not find a built jar under "Window Ver\build2\compose\jars".
    echo Build it first:  gradlew.bat :window-ver:packageUberJarForCurrentOS
    pause
    exit /b 1
)
start "" "%JAVA_EXE%" -jar "%JAR%"
