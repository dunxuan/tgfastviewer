param(
    [string]$Sdk = 'C:\Users\dunxuan\Downloads\telegram\telegram\android-sdk',
    [string]$BuildTools = '36.0.0',
    [string]$Platform = 'android-36'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$bt = Join-Path $Sdk "build-tools\$BuildTools"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome 'bin\java.exe'))) {
    $javaHome = 'C:\Users\dunxuan\scoop\apps\temurin21-jdk\current'
}
$env:JAVA_HOME = $javaHome
$env:PATH = "$javaHome\bin;$bt;$env:PATH"

$javac = Join-Path $javaHome 'bin\javac.exe'
$jarTool = Join-Path $javaHome 'bin\jar.exe'
$keytool = Join-Path $javaHome 'bin\keytool.exe'
$python = 'C:\Users\dunxuan\AppData\Local\Programs\Python\Python314\python.exe'

foreach ($tool in @($javac, $jarTool, $keytool, $python, (Join-Path $bt 'd8.bat'), (Join-Path $bt 'apksigner.bat'), $androidJar)) {
    if (-not (Test-Path $tool)) { throw "missing tool: $tool" }
}

$libXposed = Join-Path $root 'libs\libxposed-api-102.0.0.jar'
$sources = Join-Path $root 'app\src\main\java'
$resources = Join-Path $root 'app\src\main\resources'
$build = Join-Path $root 'build'
$classes = Join-Path $build 'classes'
$dexDir = Join-Path $build 'dex'
$staging = Join-Path $build 'staging'
$keystore = Join-Path $root 'module.keystore'
$outDir = Join-Path $root 'out'

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classes, $dexDir, $staging, $outDir | Out-Null

Write-Host '[1/5] compile java'
$sourceFiles = Get-ChildItem -Path $sources -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -source 8 -target 8 -encoding UTF-8 -nowarn -classpath "$androidJar;$libXposed" -d $classes @sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '[2/5] dex'
$classesJar = Join-Path $build 'classes.jar'
& $jarTool cf $classesJar -C $classes .
& (Join-Path $bt 'd8.bat') --release --min-api 23 --lib $androidJar --classpath $libXposed --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '[3/5] package'
Copy-Item (Join-Path $resources 'META-INF') -Destination $staging -Recurse -Force
$unsigned = Join-Path $build 'unsigned.apk'
& $python (Join-Path $root 'tools\make_apk.py') --out $unsigned --dex (Join-Path $dexDir 'classes.dex') --staging $staging
if ($LASTEXITCODE -ne 0) { throw 'package failed' }

Write-Host '[4/5] align'
$signedInput = $unsigned
$zipalign = Join-Path $bt 'zipalign.exe'
if (Test-Path $zipalign) {
    $aligned = Join-Path $build 'aligned.apk'
    & $zipalign -p -f 4 $unsigned $aligned
    if ($LASTEXITCODE -eq 0) { $signedInput = $aligned } else { Write-Host 'zipalign unavailable, signing unaligned' }
}

Write-Host '[5/5] sign'
if (-not (Test-Path $keystore)) {
    & $keytool -genkeypair -keystore $keystore -alias modulekey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=TgFastViewer, OU=Module, O=dunxuan' | Out-Null
}
$apk = Join-Path $outDir 'TgFastViewer-1.4.5.apk'
if (Test-Path $apk) { Remove-Item $apk -Force }
& (Join-Path $bt 'apksigner.bat') sign --ks $keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias modulekey --out $apk $signedInput
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }
& (Join-Path $bt 'apksigner.bat') verify --print-certs $apk
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$info = Get-Item $apk
Write-Host ("APK  " + $info.FullName)
Write-Host ("SIZE " + [math]::Round($info.Length / 1KB, 1) + " KB")




