@echo off
chcp 65001 > nul
echo Running File Integrity Monitor...
mvn clean test
if errorlevel 1 goto end
mvn exec:java
:end
pause
