$ErrorActionPreference = 'Stop'
# Offline tests for the filter catalogs, group injection and preset selection (plain JVM).
$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$sdk = (Get-Content (Join-Path $project 'local.properties') | Where-Object { $_ -like 'sdk.dir=*' }) -replace '^sdk.dir=', ''
$android = Get-ChildItem (Join-Path $sdk 'platforms') -Directory | Sort-Object { [int]($_.Name -replace '\D', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName 'android.jar' } | Where-Object { Test-Path $_ } | Select-Object -First 1
$xposed = Join-Path $project 'app/libs/api-82.jar'
$out = Join-Path $env:TEMP 'jiege_offline_tests'
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $out | Out-Null

# api-82.jar is compile-only stubs; the tests run against a reflection stand-in (shim/) first.
& javac -encoding UTF-8 -d $out -cp $xposed (Join-Path $PSScriptRoot 'shim/de/robv/android/xposed/XposedHelpers.java')
if ($LASTEXITCODE -ne 0) { throw 'shim compile failed' }

$sources = @(Get-ChildItem (Join-Path $project 'app/src/main/java/local/jiege/hook/filters') -Filter *.java | ForEach-Object FullName)
$sources += (Join-Path $project 'app/src/main/java/local/jiege/hook/common/Log.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/hook/common/Diagnostics.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/DiagnosticsContract.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/hook/common/Settings.java')
# FilterGroupInjector reaches the camera through Symbols (DexKit-backed); compile against the DexKit AAR.
$sources += (Join-Path $project 'app/src/main/java/local/jiege/hook/common/Symbols.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/hook/common/SymbolSearch.java')
$dexkitAar = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.luckypray/dexkit') -Recurse -Filter 'dexkit-*.aar' |
    Where-Object { $_.Name -notlike '*sources*' } | Sort-Object Name -Descending | Select-Object -First 1
if (-not $dexkitAar) { throw 'DexKit AAR not in the Gradle cache (run a Gradle build once)' }
& tar -xf $dexkitAar.FullName -C $out classes.jar
if ($LASTEXITCODE -ne 0) { throw 'DexKit classes not extracted' }
$kotlin = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib') -Recurse -Filter 'kotlin-stdlib-2*.jar' |
    Where-Object { $_.Name -notlike '*sources*' } | Select-Object -First 1
if (-not $kotlin) { throw 'kotlin-stdlib not in the Gradle cache' }
$xposed = "$xposed;$(Join-Path $out 'classes.jar');$($kotlin.FullName)"
$sources += @(Get-ChildItem (Join-Path $project 'app/src/main/java/local/jiege/lut') -Filter *.java | ForEach-Object FullName)
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/MasterFilterContract.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/MasterFilterStore.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/ModuleSettings.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/LaunchModes.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/ModelPresets.java')
$sources += (Join-Path $PSScriptRoot 'MasterLooksOfflineTest.java')
$sources += (Join-Path $project 'app/src/main/java/local/jiege/settings/MasterLooks.java')
$sources += (Join-Path $PSScriptRoot 'StoreOfflineTest.java')
$sources += (Join-Path $PSScriptRoot 'FiltersOfflineTest.java')
$sources += (Join-Path $PSScriptRoot 'LutBakerOfflineTest.java')
& javac -encoding UTF-8 -d $out -cp "$out;$xposed;$android" $sources
if ($LASTEXITCODE -ne 0) { throw 'offline test compile failed' }
& java '-Dfile.encoding=UTF-8' -cp "$out;$xposed;$android" FiltersOfflineTest
if ($LASTEXITCODE -ne 0) { throw 'offline tests failed' }
& java '-Dfile.encoding=UTF-8' -cp "$out;$xposed;$android" LutBakerOfflineTest $project
if ($LASTEXITCODE -ne 0) { throw 'LUT baker parity test failed' }
& java '-Dfile.encoding=UTF-8' -cp "$out;$xposed;$android" StoreOfflineTest $project
if ($LASTEXITCODE -ne 0) { throw 'store tests failed' }

& java -cp $out MasterLooksOfflineTest
if ($LASTEXITCODE -ne 0) { throw "master colour tests failed" }
