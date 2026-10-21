$ErrorActionPreference = 'Stop'
# Offline tests of the header-only native stages (host MSVC). Leaves the binary in %TEMP%\jiege_native_tests.
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vs = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vs) { throw 'MSVC (VC.Tools.x86.x64) not installed' }
$vcvars = Join-Path $vs 'VC\Auxiliary\Build\vcvars64.bat'
$out = Join-Path $env:TEMP 'jiege_native_tests'
New-Item -ItemType Directory -Force $out | Out-Null
$source = Join-Path $PSScriptRoot 'FxBlueOfflineTest.cpp'
$exe = Join-Path $out 'FxBlueOfflineTest.exe'
cmd /c "`"$vcvars`" >nul && cl /nologo /utf-8 /std:c++17 /O2 /EHsc /W4 /D_CRT_SECURE_NO_WARNINGS /Fo`"$out\\`" /Fe`"$exe`" `"$source`""
if ($LASTEXITCODE -ne 0) { throw 'FxBlueOfflineTest compile failed' }
& $exe
if ($LASTEXITCODE -ne 0) { throw 'FxBlueOfflineTest failed' }
