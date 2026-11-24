param()
$adb = 'C:\Users\dunxuan\Downloads\telegram\TgFastViewer\pt\adb.exe'
$apk = 'C:\Users\dunxuan\Downloads\telegram\TgFastViewer\out\TgFastViewer-1.3.1.apk'
$log = 'C:\Users\dunxuan\Downloads\telegram\TgFastViewer\wait-install.log'
New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
"[$(Get-Date -Format o)] waiting for phone 192.168.1.108" | Out-File $log -Encoding UTF8

for ($i = 0; $i -lt 90; $i++) {
    $ok = Test-Connection -ComputerName 192.168.1.108 -Count 1 -Quiet 2>$null
    if (-not $ok) { Start-Sleep -Seconds 10; continue }
    "[$(Get-Date -Format o)] ping ok" | Out-File $log -Append -Encoding UTF8
    & $adb kill-server 2>&1 | Out-Null
    & $adb start-server 2>&1 | Out-Null
    & $adb connect 192.168.1.108:5555 2>&1 | Out-File $log -Append -Encoding UTF8
    Start-Sleep -Seconds 3
    $state = (& $adb devices 2>&1) -join "`n"
    if ($state -notmatch '192\.168\.1\.108:5555\s+device') {
        "[$(Get-Date -Format o)] not ready" | Out-File $log -Append -Encoding UTF8
        Start-Sleep -Seconds 10
        continue
    }
    "[$(Get-Date -Format o)] installing" | Out-File $log -Append -Encoding UTF8
    & $adb install -r $apk 2>&1 | Out-File $log -Append -Encoding UTF8
    & $adb logcat -c 2>&1 | Out-Null
    & $adb shell am force-stop org.telegram.messenger 2>&1 | Out-File $log -Append -Encoding UTF8
    Start-Sleep -Seconds 1
    & $adb shell input keyevent KEYCODE_WAKEUP 2>&1 | Out-Null
    & $adb shell am start -n org.telegram.messenger/org.telegram.ui.LaunchActivity 2>&1 | Out-File $log -Append -Encoding UTF8
    Start-Sleep -Seconds 8
    "[$(Get-Date -Format o)] module logs:" | Out-File $log -Append -Encoding UTF8
    & $adb logcat -d 2>&1 | Select-String -Pattern 'LSPosedFramework.*\[io\.github\.dunxuan\.tgfastviewer' | Out-File $log -Append -Encoding UTF8
    "[$(Get-Date -Format o)] done" | Out-File $log -Append -Encoding UTF8
    break
}
