@echo off
setlocal
where javac >nul 2>nul
if %errorlevel% equ 0 (
    set "JAVAC=javac"
    set "JAVA=java"
) else (
    set "JAVAC=C:\Program Files\Java\jdk-26.0.1\bin\javac.exe"
    set "JAVA=C:\Program Files\Java\jdk-26.0.1\bin\java.exe"
)
if not exist out mkdir out
"%JAVAC%" -d out src\com\swiftcab\*.java
if %errorlevel% neq 0 exit /b %errorlevel%
"%JAVA%" -cp out com.swiftcab.Main
