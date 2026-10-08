$ErrorActionPreference = 'Stop'
$coreToolDir = Join-Path $PSScriptRoot 'tools'
New-Item -ItemType Directory -Path $coreToolDir -Force | Out-Null
$coreDependencies = @(
    @{ Artifact='asm'; File='asm.jar'; Sha='876eab6a83daecad5ca67eb9fcabb063c97b5aeb8cf1fca7a989ecde17522051' },
    @{ Artifact='asm-tree'; File='asm-tree.jar'; Sha='14b7880cb7c85eed101e2710432fc3ffb83275532a6a894dc4c4095d49ad59f1' },
    @{ Artifact='asm-util'; File='asm-util.jar'; Sha='8ba0460ecb28fd0e2980e5f3ef3433a513a457bc077f81a53bdc75b587a08d15' },
    @{ Artifact='asm-analysis'; File='asm-analysis.jar'; Sha='e640732fbcd3c6271925a504f125e38384688f4dfbbf92c8622dfcee0d09edb9' }
)
foreach ($coreDependency in $coreDependencies) {
    $coreTarget = Join-Path $coreToolDir $coreDependency.File
    if (-not (Test-Path -LiteralPath $coreTarget)) {
        $coreArtifact = $coreDependency.Artifact
        Invoke-WebRequest -Uri "https://repo.maven.apache.org/maven2/org/ow2/asm/$coreArtifact/9.8/$coreArtifact-9.8.jar" -OutFile $coreTarget
    }
    $coreActualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $coreTarget).Hash
    if ($coreActualHash -ne $coreDependency.Sha) { throw "Dependency checksum mismatch: $coreTarget" }
}
Write-Output 'ASM 9.8 dependencies verified. Use JDK 25 with build.ps1 -JdkPath <path>.'
