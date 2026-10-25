$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot '.core-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null

# Auto-detect javac/java if not on PATH
$javacCmd = Get-Command javac -ErrorAction SilentlyContinue
if (-not $javacCmd) {
    $candidates = @(
        "$env:JAVA_HOME\bin",
        "$env:APPDATA\squidservers\java\jdk-25\bin",
        "C:\Program Files\Android\Android Studio\jbr\bin",
        "C:\Program Files\Java\jdk*\bin"
    )
    foreach ($cand in $candidates) {
        $matches = Get-ChildItem -Path $cand -Filter "javac.exe" -ErrorAction SilentlyContinue
        if ($matches) {
            $env:PATH = "$($matches[0].DirectoryName);$env:PATH"
            break
        }
    }
}

$sources = @(Get-ChildItem (Join-Path $projectRoot 'mesh/src/main/java/org/ghostnet/mesh/core') -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $projectRoot 'app/src/testCore/java/org/ghostnet/mesh/core/RouterChecks.java'
& javac -d $classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes org.ghostnet.mesh.core.RouterChecks
exit $LASTEXITCODE

