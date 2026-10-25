$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $projectRoot '.core-classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem (Join-Path $projectRoot 'mesh/src/main/java/org/ghostnet/mesh/core') -Filter '*.java' | ForEach-Object FullName)
$sources += Join-Path $projectRoot 'app/src/testCore/java/org/ghostnet/mesh/core/RouterChecks.java'
& javac -d $classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $classes org.ghostnet.mesh.core.RouterChecks
exit $LASTEXITCODE
