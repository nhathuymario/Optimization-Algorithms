@echo off
echo Loading environment variables from .env...
for /f "tokens=1,* delims==" %%A in (.env) do (
    if not "%%A"=="" if not "%%B"=="" (
        set %%A=%%B
    )
)
echo Starting Maven build and execution...
mvn clean compile exec:java
