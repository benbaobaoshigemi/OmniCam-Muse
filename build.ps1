$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$python = if ($env:OMNICAM_PYTHON) { $env:OMNICAM_PYTHON } else { 'python' }
$version = ((Get-Content (Join-Path $project 'module/module.prop') | Where-Object { $_ -like 'version=*' }) -replace '^version=(Muse )?', '').Trim()
$sdkProperties = Join-Path $project 'local.properties'
if (-not (Test-Path -LiteralPath $sdkProperties)) {
    if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME or create local.properties with sdk.dir.' }
    Set-Content -LiteralPath $sdkProperties -Encoding utf8NoBOM -Value ('sdk.dir=' + $env:ANDROID_HOME.Replace('\', '/'))
}
& $python (Join-Path $project 'tools/mastercolor/check_catalog.py')
if ($LASTEXITCODE -ne 0) { throw 'Master colour catalog validation failed.' }
& (Join-Path $project 'tools/offline_tests/run.ps1')
& 'C:/Program Files/Git/bin/bash.exe' -n (Join-Path $project 'module/post-fs-data.sh') (Join-Path $project 'module/post-mount.sh') (Join-Path $project 'module/service.sh')
if ($LASTEXITCODE -ne 0) { throw 'Module shell syntax validation failed.' }
$drive = (70..90 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -Last 1)
if (-not $drive) { throw 'No free drive letter for the Android build.' }
subst "$($drive):" $project
if ($LASTEXITCODE -ne 0) { throw 'Unable to map the build directory.' }
try {
    Push-Location "$($drive):\"
    try {
        & .\gradlew.bat :app:assembleRelease --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }
    } finally { Pop-Location }
} finally { subst "$($drive):" /D }
$signed = Test-Path -LiteralPath (Join-Path $project 'port.keystore')
if ($env:OMNICAM_KEYSTORE) { $signed = Test-Path -LiteralPath $env:OMNICAM_KEYSTORE }
$apkFile = if ($signed) { 'app-release.apk' } else { 'app-release-unsigned.apk' }
$built = Join-Path $project "app/build/outputs/apk/release/$apkFile"
if (-not (Test-Path -LiteralPath $built)) { throw "Missing output: $built" }
if ($signed) {
    Get-ChildItem -Path (Join-Path $project 'module') -Filter 'omnicam-muse-*.apk' | Remove-Item
    Copy-Item -LiteralPath $built -Destination (Join-Path $project "module/omnicam-muse-$version.apk")
    & $python (Join-Path $project 'tools/zip_module.py') (Join-Path $project 'module') (Join-Path $project "OmniCam-Muse-$version-KSU.zip")
    if ($LASTEXITCODE -ne 0) { throw 'Module packaging failed.' }
} else {
    Write-Output "Unsigned APK: $built. Configure OMNICAM_KEYSTORE to produce a signed module."
}
