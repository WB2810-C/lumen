@echo off
setlocal EnableExtensions
title PlaizierOS - TV installer
cd /d "%~dp0"
set "ADB=platform-tools\adb.exe"
set "PKG=com.wolfram.lumen"
set "MODE=install"
if /i "%~1"=="restore" set "MODE=restore"
if /i "%~1"=="report" set "MODE=report"
if /i "%~1"=="sd" set "MODE=sd"

echo.
echo   ==========================================
if "%MODE%"=="restore" echo     Back to the Google TV home screen
if "%MODE%"=="install" echo     PlaizierOS  -  put it on your TV
if "%MODE%"=="report" echo     PlaizierOS report  -  what the TV says
if "%MODE%"=="sd" echo     Back to 1280x720 drawing
echo   ==========================================
echo.

if not "%MODE%"=="install" goto :getadb
if exist "PlaizierOS.apk" goto :getadb
echo   PlaizierOS.apk is not next to this file.
echo   Unzip PlaizierOS-installer.zip first: right-click it, Extract All, then open that folder.
goto :fail

:getadb
if exist "%ADB%" goto :tvsteps
echo   Downloading Google's small adb tool [about 7 MB, no Android Studio]...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile 'platform-tools.zip'; Expand-Archive -Force 'platform-tools.zip' '.'; Remove-Item 'platform-tools.zip' } catch { exit 1 }"
if exist "%ADB%" goto :tvsteps
echo   Couldn't download adb. Check the laptop's internet connection and try again.
goto :fail

:tvsteps
%ADB% start-server >nul 2>&1
echo.
echo   On the TV, once:
echo     1. Settings ^> System ^> About ^> click "Android TV OS build" 7 times
echo        until it says you are a developer
echo     2. Settings ^> System ^> Developer options ^> turn on "USB debugging"
echo     3. Settings ^> Network ^& Internet ^> your Wi-Fi ^> write down the IP address
echo.
echo   The laptop and the TV must be on the same Wi-Fi.
echo.

:askip
set "TVIP="
set /p "TVIP=  TV's IP address [like 192.168.1.50]: "
if not defined TVIP goto :askip
set "TVIP=%TVIP: =%"
set "TARGET=%TVIP%:5555"
echo %TVIP%| find ":" >nul && set "TARGET=%TVIP%"

echo.
echo   Connecting... Look at the TV: if it asks "Allow USB debugging?",
echo   tick "Always allow from this computer" and choose OK.
%ADB% connect %TARGET% > "%TEMP%\lumen-connect.txt" 2>&1
type "%TEMP%\lumen-connect.txt"
findstr /i /c:"connected to" /c:"authenticate" "%TEMP%\lumen-connect.txt" >nul
if errorlevel 1 goto :pair
call :waitdevice
if errorlevel 1 goto :noallow
goto :connected

:pair
echo.
echo   The TV didn't answer on the usual port. Use Wireless debugging instead:
echo     On the TV: Settings ^> System ^> Developer options ^> Wireless debugging ^> turn it on,
echo     then choose "Pair device with pairing code".
echo.
set "PAIRADDR="
set /p "PAIRADDR=  IP address and port shown under the code [like 192.168.1.50:37123]: "
set "PAIRCODE="
set /p "PAIRCODE=  The 6-digit code: "
%ADB% pair %PAIRADDR% %PAIRCODE%
echo.
echo   Now close the code on the TV. The Wireless debugging screen shows
echo   IP address ^& Port - that port is a different one [like 192.168.1.50:41234].
set "TARGET="
set /p "TARGET=  That IP address and port: "
%ADB% connect %TARGET%
call :waitdevice
if errorlevel 1 goto :noconnect
goto :connected

:connected
echo.
echo   Connected to the TV.
if "%MODE%"=="restore" goto :restore
if "%MODE%"=="report" goto :reportonly
if "%MODE%"=="sd" goto :res720

echo.
echo   Installing PlaizierOS... this takes a moment.
echo   If the TV shows a Play Protect message, choose "Install anyway".
%ADB% -s %TARGET% install -r PlaizierOS.apk > "%TEMP%\lumen-install.txt" 2>&1
type "%TEMP%\lumen-install.txt"
findstr /c:"Success" "%TEMP%\lumen-install.txt" >nul
if not errorlevel 1 goto :installed
findstr /c:"UPDATE_INCOMPATIBLE" /c:"SIGNATURES" "%TEMP%\lumen-install.txt" >nul
if errorlevel 1 goto :installfail
echo   An older PlaizierOS made with a different key is on the TV. Replacing it...
%ADB% -s %TARGET% uninstall %PKG% >nul 2>&1
%ADB% -s %TARGET% install PlaizierOS.apk > "%TEMP%\lumen-install.txt" 2>&1
type "%TEMP%\lumen-install.txt"
findstr /c:"Success" "%TEMP%\lumen-install.txt" >nul
if errorlevel 1 goto :installfail

:installed
echo.
echo   Switching on the microphone and Now Playing for PlaizierOS...
%ADB% -s %TARGET% shell pm grant %PKG% android.permission.RECORD_AUDIO >nul 2>&1
%ADB% -s %TARGET% shell cmd notification allow_listener %PKG%/%PKG%.MediaListener >nul 2>&1
%ADB% -s %TARGET% shell appops set %PKG% SYSTEM_ALERT_WINDOW allow >nul 2>&1
echo   Setting the TV picture to 1080p...
%ADB% -s %TARGET% shell cmd display set-user-preferred-display-mode 1920 1080 60 >nul 2>&1
echo.
echo   Your TV lets apps draw at 1280x720 and stretches that to its 1920x1080 screen.
echo   PlaizierOS can ask it to draw everything at 1920x1080 instead. On some TVs that is
echo   sharper; on others the TV's chip still shrinks it, and then it is only a bit slower.
echo   You can always undo it with Back-to-720p.bat.
choice /c YN /n /m "  Draw at 1920x1080? [Y/N] "
if errorlevel 2 goto :skiphd
call :sethd
:skiphd
echo   Opening PlaizierOS on the TV...
%ADB% -s %TARGET% shell am start -n %PKG%/.MainActivity >nul 2>&1
echo.
choice /c YN /n /m "  Do you see PlaizierOS on the TV now? [Y/N] "
if errorlevel 2 goto :notshowing

echo.
echo   Make PlaizierOS the home screen? Then the Home button on the remote opens PlaizierOS.
echo   You can undo it any time with Back-to-Google-TV.bat.
choice /c YN /n /m "  Make PlaizierOS the home screen? [Y/N] "
if errorlevel 2 goto :donenohome
%ADB% -s %TARGET% shell cmd package set-home-activity --user 0 %PKG%/.MainActivity >nul 2>&1
%ADB% -s %TARGET% shell pm disable-user --user 0 com.google.android.apps.tv.launcherx >nul 2>&1
%ADB% -s %TARGET% shell pm disable-user --user 0 com.google.android.tungsten.setupwraith >nul 2>&1
%ADB% -s %TARGET% shell input keyevent KEYCODE_HOME >nul 2>&1
echo.
echo   Done! Press Home on the remote and PlaizierOS opens.
echo   To update PlaizierOS later, just run this file again with the new PlaizierOS.apk.
call :report
goto :end

:donenohome
echo.
echo   Done! PlaizierOS is in the TV's app list. Your normal home screen stays.
call :report
goto :end

:reportonly
call :report
goto :end

:notshowing
echo.
echo   Then PlaizierOS didn't start properly. Your normal home screen stays as it is.
echo   Saving what the TV reported in plaizieros-crash.txt - send that file to Claude.
%ADB% -s %TARGET% logcat -d -b crash > plaizieros-crash.txt 2>&1
%ADB% -s %TARGET% logcat -d -t 400 *:E >> plaizieros-crash.txt 2>&1
goto :end

:restore
echo   Switching the Google TV home screen back on...
%ADB% -s %TARGET% shell pm enable --user 0 com.google.android.apps.tv.launcherx >nul 2>&1
%ADB% -s %TARGET% shell pm enable --user 0 com.google.android.tungsten.setupwraith >nul 2>&1
%ADB% -s %TARGET% shell cmd package set-home-activity --user 0 com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.home.HomeActivity >nul 2>&1
%ADB% -s %TARGET% shell wm size reset >nul 2>&1
%ADB% -s %TARGET% shell wm density reset >nul 2>&1
echo.
choice /c YN /n /m "  Also remove PlaizierOS from the TV? [Y/N] "
if errorlevel 2 goto :restored
%ADB% -s %TARGET% uninstall %PKG%
:restored
%ADB% -s %TARGET% shell input keyevent KEYCODE_HOME >nul 2>&1
echo.
echo   Done. Press Home on the remote: the Google TV home screen is back.
goto :end

:noallow
echo.
echo   The TV didn't allow the connection. Run this file again and choose OK on the TV.
goto :fail

:noconnect
echo.
echo   Couldn't connect to the TV. Check: same Wi-Fi, USB debugging on, and the right IP address.
goto :fail

:installfail
echo.
echo   The install didn't work - the reason is just above. Send a photo of this window to Claude.
goto :fail

:fail
echo.
pause
exit /b 1

:end
%ADB% disconnect %TARGET% >nul 2>&1
echo.
pause
exit /b 0

rem Asks the TV to draw at 1920x1080, keeping everything the same size on screen.
:sethd
set "DENS="
for /f "tokens=3" %%d in ('%ADB% -s %TARGET% shell wm density ^| find "Physical"') do set "DENS=%%d"
if not defined DENS set "DENS=213"
set /a NEWD=DENS*3/2
%ADB% -s %TARGET% shell wm size 1920x1080 >nul 2>&1
%ADB% -s %TARGET% shell wm density %NEWD% >nul 2>&1
echo   The TV now draws at 1920x1080 [density %NEWD%].
exit /b 0

:res720
echo   Switching back to the TV's own 1280x720 drawing...
%ADB% -s %TARGET% shell wm size reset >nul 2>&1
%ADB% -s %TARGET% shell wm density reset >nul 2>&1
echo   Done.
goto :end

rem Writes plaizieros-report.txt: what the TV says about PlaizierOS, the picture, the internet and voice.
:report
echo.
echo   Writing plaizieros-report.txt - if something doesn't work, send that file to Claude.
timeout /t 5 /nobreak >nul
set "R=plaizieros-report.txt"
> "%R%" echo PlaizierOS report %date% %time%
>> "%R%" echo ==== TV
%ADB% -s %TARGET% shell getprop ro.product.manufacturer >> "%R%" 2>&1
%ADB% -s %TARGET% shell getprop ro.product.model >> "%R%" 2>&1
%ADB% -s %TARGET% shell getprop ro.build.version.release >> "%R%" 2>&1
%ADB% -s %TARGET% shell getprop ro.config.low_ram >> "%R%" 2>&1
>> "%R%" echo ==== Picture
%ADB% -s %TARGET% shell wm size >> "%R%" 2>&1
%ADB% -s %TARGET% shell wm density >> "%R%" 2>&1
%ADB% -s %TARGET% shell getprop ro.surface_flinger.max_graphics_width >> "%R%" 2>&1
%ADB% -s %TARGET% shell getprop ro.surface_flinger.max_graphics_height >> "%R%" 2>&1
%ADB% -s %TARGET% shell cmd display get-user-preferred-display-mode >> "%R%" 2>&1
%ADB% -s %TARGET% shell "dumpsys display | grep -iE 'mActiveMode|mDefaultMode|supportedModes|mUserPreferredMode|mBaseDisplayInfo' | head -20" >> "%R%" 2>&1
>> "%R%" echo ==== Internet
%ADB% -s %TARGET% shell date >> "%R%" 2>&1
%ADB% -s %TARGET% shell settings get global private_dns_mode >> "%R%" 2>&1
%ADB% -s %TARGET% shell settings get global private_dns_specifier >> "%R%" 2>&1
%ADB% -s %TARGET% shell ping -c 1 -W 3 api.open-meteo.com >> "%R%" 2>&1
%ADB% -s %TARGET% shell ping -c 1 -W 3 www.vrt.be >> "%R%" 2>&1
>> "%R%" echo ==== Voice
%ADB% -s %TARGET% shell cmd package query-services -a android.speech.RecognitionService >> "%R%" 2>&1
%ADB% -s %TARGET% shell cmd package query-activities -a android.speech.action.RECOGNIZE_SPEECH >> "%R%" 2>&1
>> "%R%" echo ==== PlaizierOS
%ADB% -s %TARGET% shell "dumpsys package %PKG% | grep -E 'versionName|RECORD_AUDIO|INTERNET'" >> "%R%" 2>&1
%ADB% -s %TARGET% logcat -d -s Lumen >> "%R%" 2>&1
>> "%R%" echo ==== Crashes
%ADB% -s %TARGET% logcat -d -b crash >> "%R%" 2>&1
echo   Saved plaizieros-report.txt
exit /b 0

rem Waits up to 90 seconds until the TV is connected and allowed. errorlevel 0 = ready.
:waitdevice
set /a WAITED=0
:waitloop
set "STATE="
for /f "delims=" %%s in ('%ADB% -s %TARGET% get-state 2^>nul') do set "STATE=%%s"
if "%STATE%"=="device" exit /b 0
set /a WAITED+=1
if %WAITED% geq 45 exit /b 1
if %WAITED%==3 echo   Waiting for OK on the TV...
timeout /t 2 /nobreak >nul
%ADB% connect %TARGET% >nul 2>&1
goto :waitloop
