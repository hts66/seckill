@echo off
chcp 65001 >nul
REM ============================================================
REM  秒杀系统 - 阶梯加压测试 (Windows CMD)
REM  用法: 双击运行，或在 CMD 中执行 staircase_test.bat
REM ============================================================

setlocal enabledelayedexpansion

set RESULT_DIR=stress_results
mkdir %RESULT_DIR% 2>nul

echo ==============================================
echo   秒杀系统 - 阶梯加压测试
echo   时间: %date% %time%
echo ==============================================
echo.
echo  并发数    总请求    QPS       平均响应    P99       错误     结果
echo ----------------------------------------------------------------------

REM 测试阶梯（可自行修改）
set LEVELS[0]=500:5
set LEVELS[1]=1000:10
set LEVELS[2]=2000:15
set LEVELS[3]=3000:20
set LEVELS[4]=5000:30

set PREV_RESULT=pass
set n=0

:next_level
if not defined LEVELS[%n%] goto done

REM 解析 线程数:rampup
for /f "tokens=1,2 delims=:" %%a in ("!LEVELS[%n%]!") do (
  set THREADS=%%a
  set RAMPUP=%%b
)

if "%PREV_RESULT%"=="fail" (
  echo  !THREADS!        -          -           -           -          -        跳过
  set /a n+=1
  goto next_level
)

set JTL=%RESULT_DIR%\result_t%THREADS%.jtl
set REPORT=%RESULT_DIR%\report_t%THREADS%

echo.
echo ^>^>^> 开始: %THREADS% 并发 ^(ramp-up %RAMPUP%s^) ...

REM 清理旧文件
if exist "%JTL%" del /q "%JTL%"
if exist "%REPORT%" rmdir /s /q "%REPORT%"

REM 运行 JMeter
jmeter -n ^
  -t "seckill-stress.jmx" ^
  -Jthreads=%THREADS% ^
  -Jrampup=%RAMPUP% ^
  -Jloops=1 ^
  -l "%JTL%" ^
  -e -o "%REPORT%" ^
  >nul 2>&1

if not exist "%JTL%" (
  echo  [错误] JMeter 未生成结果文件
  set PREV_RESULT=fail
  set /a n+=1
  goto next_level
)

REM 统计总请求数
for /f %%c in ('type "%JTL%" ^| find /c /v "timeStamp"') do set TOTAL=%%c

REM 统计错误数（非200响应）
set ERRORS=0
for /f "tokens=4 delims=," %%a in ('type "%JTL%" ^| findstr /v "^timeStamp"') do (
  if not "%%a"=="200" set /a ERRORS+=1
)

REM 简单输出
echo  完成: %TOTAL% 请求, %ERRORS% 错误
echo  详细报告: %REPORT%\index.html

REM 如果有错误，继续但标记
if %ERRORS% gtr 0 (
  set PREV_RESULT=warn
)

set /a n+=1
timeout /t 3 >nul
goto next_level

:done
echo.
echo ==============================================
echo   测试完成: %date% %time%
echo   报告目录: %RESULT_DIR%
echo ==============================================
endlocal
pause
