@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem

@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle startup script for Windows
@rem
@rem ##########################################################################

@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Use a constant for the wrapper JAR path
set WRAPPER_JAR="%APP_HOME%\gradle\wrapper\gradle-wrapper.jar"

@rem Attempt to set JAVA_HOME if it is not already set.
if "%JAVA_HOME%" == "" (
  set JAVA_EXE=java.exe
  %JAVA_EXE% -version >NUL 2>&1
  if %ERRORLEVEL% == 0 (
    @rem Found java.exe in PATH.
    goto init
  )
  echo ERROR: JAVA_HOME is not set and no 'java.exe' command could be found in your PATH.
  echo.
  echo Please set the JAVA_HOME variable in your environment to match the
  echo location of your Java installation.
  goto fail
)

if exist "%JAVA_HOME%\bin\java.exe" (
  set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
  goto init
)

echo ERROR: JAVA_HOME is set to an invalid directory: "%JAVA_HOME%"
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
goto fail

:init
@rem Find java.exe
if exist "%JAVA_EXE%" goto okJava

echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
echo.
echo Please set the JAVA_HOME variable in your environment to match the
echo location of your Java installation.
goto fail

:okJava
@rem Setup the command line

set CLASSPATH=%WRAPPER_JAR%

@rem Setup the JVM
set JAVA_OPTS=%DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS%
set JAVA_OPTS=%JAVA_OPTS% -Dorg.gradle.appname="%APP_BASE_NAME%"

@rem Execute Gradle
"%JAVA_EXE%" %JAVA_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
@rem End local scope for the variables with windows NT shell
if "%ERRORLEVEL%"=="0" goto mainEnd

:fail
@rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
@rem the _cmd.exe /c_ return code!
if not "" == "%GRADLE_EXIT_CONSOLE%" (
    exit /B 1
)
exit 1

:mainEnd
@rem Set variable GRADLE_EXIT_CONSOLE if you need the _script_ return code instead of
@rem the _cmd.exe /c_ return code!
if not "" == "%GRADLE_EXIT_CONSOLE%" (
   exit /B 0
)
exit 0
