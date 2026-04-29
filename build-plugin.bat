@echo off
where gradle >nul 2>nul
if %errorlevel%==0 (
  gradle buildPlugin
) else (
  echo Gradle is not installed. Open this folder in IntelliJ IDEA and run the Gradle task: buildPlugin
  exit /b 1
)
