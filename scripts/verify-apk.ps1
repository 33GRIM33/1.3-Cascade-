param([string]$SdkRoot = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (-not $SdkRoot) { throw 'Set ANDROID_HOME or pass -SdkRoot' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$apk = Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk'
$aapt = Join-Path $SdkRoot 'build-tools/35.0.0/aapt.exe'
$permissionLines = & $aapt dump permissions $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK permissions' }
$required = @('ACCESS_WIFI_STATE','CHANGE_WIFI_STATE','ACCESS_NETWORK_STATE','ACCESS_COARSE_LOCATION','ACCESS_FINE_LOCATION','BLUETOOTH_SCAN','BLUETOOTH_CONNECT','BLUETOOTH_ADVERTISE','NEARBY_WIFI_DEVICES')
foreach ($permission in $required) {
    $matches = @($permissionLines | Where-Object { $_ -match "name='android.permission.$permission'" })
    if ($matches.Count -ne 1) { throw "APK is missing permission $permission" }
    if ($matches[0] -match 'maxSdkVersion') { throw "$permission is incorrectly capped on newer Android" }
}
$badging = & $aapt dump badging $apk
if ($LASTEXITCODE -ne 0) { throw 'Could not inspect APK version' }
if (-not ($badging -match "versionCode='4' versionName='0.4'")) { throw 'This is not the audited 0.4 APK' }
if (-not ($badging -match "launchable-activity: name='org.ghostnet.mesh.DebugActivity'")) { throw 'Launcher activity missing' }
$signer = Join-Path $SdkRoot 'build-tools/35.0.0/apksigner.bat'
& $signer verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output 'APK checks passed: version 0.4, launcher, signature and all required permissions without modern-Android caps.'
