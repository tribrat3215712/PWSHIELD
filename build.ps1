param(
    [string]$JdkPath = '',
    [string]$InputJar = (Join-Path $PSScriptRoot 'original\projectzomboid.jar')
)
$ErrorActionPreference = 'Stop'
if (-not $JdkPath) {
    $coreLocalJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tools\jdk25') -Directory | Select-Object -First 1
    if (-not $coreLocalJdk) { throw 'Pass -JdkPath with a JDK 25 directory.' }
    $JdkPath = $coreLocalJdk.FullName
}
$coreJava = Join-Path $JdkPath 'bin\java.exe'
$coreJavac = Join-Path $JdkPath 'bin\javac.exe'
$coreBuildDir = Join-Path $PSScriptRoot 'build'
$coreRuntimeClasses = Join-Path $coreBuildDir 'runtime'
$coreToolClasses = Join-Path $coreBuildDir 'tool'
$coreTestClasses = Join-Path $coreBuildDir 'tests'
$coreCandidateDir = Join-Path $coreBuildDir 'candidate'
$coreDist = Join-Path $PSScriptRoot 'dist'
$corePublishedJar = Join-Path $coreDist 'projectzomboid.jar'
if ([System.IO.Path]::GetFullPath($InputJar).Equals([System.IO.Path]::GetFullPath($corePublishedJar),[System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Input JAR cannot be the published distribution JAR'
}
$coreGeneratedRoot = [System.IO.Path]::GetFullPath($coreBuildDir) + [System.IO.Path]::DirectorySeparatorChar
if (Test-Path -LiteralPath $coreBuildDir) {
    $coreBuildItem = Get-Item -LiteralPath $coreBuildDir -Force
    if (-not $coreBuildItem.PSIsContainer -or ($coreBuildItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'Generated build root must be a regular directory, not a junction or symlink'
    }
}
foreach ($coreGeneratedPath in @($coreRuntimeClasses,$coreToolClasses,$coreTestClasses,$coreCandidateDir)) {
    $coreResolvedGeneratedPath = [System.IO.Path]::GetFullPath($coreGeneratedPath)
    if (-not $coreResolvedGeneratedPath.StartsWith($coreGeneratedRoot,[System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean outside generated build directory: $coreResolvedGeneratedPath"
    }
    if (Test-Path -LiteralPath $coreResolvedGeneratedPath) {
        $coreGeneratedItem = Get-Item -LiteralPath $coreResolvedGeneratedPath -Force
        if ($coreGeneratedItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) { throw 'Generated directory cannot be a junction or symlink' }
        Remove-Item -LiteralPath $coreResolvedGeneratedPath -Recurse -Force
    }
}
if (Test-Path -LiteralPath $coreDist) {
    $coreDistItem = Get-Item -LiteralPath $coreDist -Force
    if (-not $coreDistItem.PSIsContainer -or ($coreDistItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'Distribution directory must be a regular directory on the workspace volume'
    }
}
New-Item -ItemType Directory -Path $coreRuntimeClasses,$coreToolClasses,$coreTestClasses,$coreCandidateDir,$coreDist -Force | Out-Null
$coreAsm = @('asm.jar','asm-tree.jar','asm-util.jar','asm-analysis.jar') | ForEach-Object { Join-Path $PSScriptRoot ('tools\' + $_) }
foreach ($coreDependency in $coreAsm) { if (-not (Test-Path -LiteralPath $coreDependency)) { throw "Missing dependency $coreDependency (ASM 9.8)" } }
$coreAsmCp = $coreAsm -join ';'
$coreRuntimeSources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\pwshield') -Filter '*.java' | ForEach-Object FullName
& $coreJavac --release 25 -encoding UTF-8 -classpath $InputJar -d $coreRuntimeClasses @coreRuntimeSources
if ($LASTEXITCODE -ne 0) { throw 'Runtime compilation failed' }
& $coreJavac --release 25 -encoding UTF-8 -classpath $coreAsmCp -d $coreToolClasses (Join-Path $PSScriptRoot 'src\build\CorePatcher.java')
if ($LASTEXITCODE -ne 0) { throw 'Patcher compilation failed' }
$corePatchedJar = Join-Path $coreCandidateDir 'projectzomboid.jar'
& $coreJava -Xverify:all -classpath "$coreToolClasses;$coreAsmCp" build.CorePatcher $InputJar $coreRuntimeClasses $corePatchedJar
if ($LASTEXITCODE -ne 0) { throw 'Core patching failed' }
$coreTests = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'tests') -Filter '*.java' | ForEach-Object FullName
& $coreJavac --release 25 -encoding UTF-8 -classpath $corePatchedJar -d $coreTestClasses @coreTests
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$coreTestCp = "$corePatchedJar;$coreTestClasses"
$coreTestConfigArg = '-Dpwshield.config=' + (Join-Path $PSScriptRoot 'tests\test.properties')
& $coreJava -Xverify:all $coreTestConfigArg -classpath $coreTestCp pwshield.CoreTests
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
& $coreJava -Xverify:all $coreTestConfigArg -classpath $coreTestCp pwshield.BatchLifecycleTests scheduler
if ($LASTEXITCODE -ne 0) { throw 'Batch recovery tests failed' }
foreach ($coreLegacyFile in @('batch-queues-disabled.properties','batch-shield-disabled.properties')) {
    $coreLegacyConfig = '-Dpwshield.config=' + (Join-Path $PSScriptRoot ('tests\' + $coreLegacyFile))
    & $coreJava -Xverify:all $coreLegacyConfig -classpath $coreTestCp pwshield.BatchLifecycleTests legacy
    if ($LASTEXITCODE -ne 0) { throw 'Legacy batch cleanup tests failed' }
}
& $coreJava -Xverify:all -classpath $coreTestCp pwshield.ConfigAndMetricsTests (Join-Path $PSScriptRoot 'tests')
if ($LASTEXITCODE -ne 0) { throw 'Configuration and metrics tests failed' }
& $coreJava --enable-native-access=ALL-UNNAMED -Xverify:all $coreTestConfigArg -classpath $coreTestCp pwshield.ProtocolIntegrationTests
if ($LASTEXITCODE -ne 0) { throw 'Wire protocol integration tests failed' }
& $coreJava --sun-misc-unsafe-memory-access=allow -Xverify:all $coreTestConfigArg -classpath $coreTestCp pwshield.VehicleAuthorizationTests
if ($LASTEXITCODE -ne 0) { throw 'Vehicle authorization integration tests failed' }
& $coreJava -Xverify:all -classpath $coreTestCp pwshield.VerifyClasses
if ($LASTEXITCODE -ne 0) { throw 'JVM bytecode verification failed' }
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pwshield.properties') -Destination $coreDist
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'original\serialize.lua'),(Join-Path $PSScriptRoot 'original\stdlib.lua') -Destination $coreDist
if (Test-Path -LiteralPath $corePublishedJar) {
    $corePublishedItem = Get-Item -LiteralPath $corePublishedJar -Force
    if ($corePublishedItem.PSIsContainer -or ($corePublishedItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'Published JAR must be a regular file, not a directory, junction or symlink'
    }
    [System.IO.File]::Replace($corePatchedJar,$corePublishedJar,[System.Management.Automation.Language.NullString]::Value)
} else {
    [System.IO.File]::Move($corePatchedJar,$corePublishedJar)
}
Get-FileHash -Algorithm SHA256 -LiteralPath $corePublishedJar
