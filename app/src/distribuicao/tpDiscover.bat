@echo off
rem tpDiscover - requer Java 8 (JRE 8) ou superior instalado.
rem Sem argumentos abre a interface grafica; com argumentos roda no terminal (ex.: tpDiscover.bat --help).
setlocal
set "DIR=%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java nao encontrado. Instale o Java 8 ^(JRE^) e tente novamente.
  pause
  exit /b 1
)
if "%~1"=="" (
  start "" javaw -jar "%DIR%tpDiscover.jar"
) else (
  java -jar "%DIR%tpDiscover.jar" %*
)
