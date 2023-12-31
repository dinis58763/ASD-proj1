@echo off
setlocal enabledelayedexpansion

set /A number_processes=2

call mvn compile package

start java -cp target/asdProj.jar Main interface=lo port=10101

for /l %%i in (1, 1, %number_processes%) do (
    set /A portNumber=10101+%%i
    start cmd /k "java -cp target/asdProj.jar Main interface=lo port=!portNumber! contact=127.0.0.1:10101"
    timeout /t 2

)

endlocal