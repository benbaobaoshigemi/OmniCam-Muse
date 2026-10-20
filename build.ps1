# Native tools (java/gradle/apksigner) write progress and notices to stderr. PowerShell 5.1 turns
# a native stderr write into an error record, and under 'Stop' the first one becomes a terminating
# NativeCommandError even when the tool exits 0 (javac's "unchecked operation" note is enough to
# abort the script). Silence those records and gate every native step on $LASTEXITCODE instead.
$ErrorActionPreference = 'SilentlyContinue'
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
if ($LASTEXITCODE -ne 0) { throw 'Offline tests failed.' }
& 'C:/Program Files/Git/bin/bash.exe' -n (Join-Path $project 'module/post-fs-data.sh') (Join-Path $project 'module/post-mount.sh') (Join-Path $project 'module/service.sh')
if ($LASTEXITCODE -ne 0) { throw 'Module shell syntax validation failed.' }
$drive = (70..90 | ForEach-Object { [char]$_ } | Where-Object { -not (Test-Path "$($_):\") } | Select-Object -Last 1)
if (-not $drive) { throw 'No free drive letter for the Android build.' }
subst "$($drive):" $project
if ($LASTEXITCODE -ne 0) { throw 'Unable to map the build directory.' }
$buildStart = Get-Date
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
# Without a release keystore the output stays unsigned and the phone refuses the update over the
# installed app, so a plain .\build.ps1 used to leave module/ stale with no error. Fall back to the
# standard debug keystore - the certificate the module has been signed with all along - and align
# first, the way the manual step did.
$debugKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not $signed -and (Test-Path -LiteralPath $debugKey)) {
    $sdkDir = ((Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', '').Trim()
    $buildTools = Get-ChildItem -Path (Join-Path $sdkDir.Replace('/', '\') 'build-tools') -Directory |
        Sort-Object Name | Select-Object -Last 1
    $releaseDir = Join-Path $project 'app/build/outputs/apk/release'
    $unsigned = Join-Path $releaseDir 'app-release-unsigned.apk'
    $aligned = Join-Path $releaseDir 'app-release-aligned.apk'
    $signedApk = Join-Path $releaseDir 'app-release.apk'
    & (Join-Path $buildTools.FullName 'zipalign.exe') -f -p 4 $unsigned $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }
    & (Join-Path $buildTools.FullName 'apksigner.bat') sign --ks $debugKey --ks-pass pass:android `
        --ks-key-alias androiddebugkey --key-pass pass:android --out $signedApk $aligned
    if ($LASTEXITCODE -ne 0) { throw 'apksigner failed.' }
    $signed = $true
    $apkFile = 'app-release.apk'
}
$built = Join-Path $project "app/build/outputs/apk/release/$apkFile"
if (-not (Test-Path -LiteralPath $built)) { throw "Missing output: $built" }
# The freshness gate: a Gradle task can fail while the launcher still reports success (seen with
# Kotlin's incremental cache: "this and base files have different roots", gradle exit 0 and the
# previous APK left in place). Packaging that would ship a stale build under a new version, so the
# artifact has to have been written by this run.
if ((Get-Item -LiteralPath $built).LastWriteTime -lt $buildStart) {
    throw "Stale output: $built was not rebuilt by this run (Gradle failed silently)."
}
if ($signed) {
    Get-ChildItem -Path (Join-Path $project 'module') -Filter 'omnicam-muse-*.apk' | Remove-Item
    Copy-Item -LiteralPath $built -Destination (Join-Path $project "module/omnicam-muse-$version.apk")
    & $python (Join-Path $project 'tools/zip_module.py') (Join-Path $project 'module') (Join-Path $project "OmniCam-Muse-$version-KSU.zip")
    if ($LASTEXITCODE -ne 0) { throw 'Module packaging failed.' }
} else {
    Write-Output "Unsigned APK: $built. Configure OMNICAM_KEYSTORE to produce a signed module."
}
