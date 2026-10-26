$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$output = Join-Path $root 'app/src/main/assets/maps/sf-civic.tsv'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $output) | Out-Null
# One small OSM data extract, not a bulk tile download. The APK never needs network maps.
$url = 'https://www.openstreetmap.org/api/0.6/map?bbox=-122.429,37.770,-122.409,37.783'
[xml]$osm = (Invoke-WebRequest -Uri $url -UserAgent 'GhostnetOfflineMap/0.6' -TimeoutSec 55).Content
$nodes = @{}
foreach ($node in $osm.osm.node) { $nodes[[string]$node.id] = $node }
$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add("MAP`tsf-civic-v1`t37.770`t-122.429`t37.783`t-122.409")
$lines.Add('# Map data from OpenStreetMap contributors. ODbL 1.0. https://www.openstreetmap.org/copyright')
$roads = @()
$areas = @()
$used = [System.Collections.Generic.HashSet[string]]::new()
foreach ($way in $osm.osm.way) {
    $tags = @{}
    foreach ($tag in $way.tag) { $tags[[string]$tag.k] = [string]$tag.v }
    if ($tags.highway -match '^(primary|secondary|tertiary|residential|unclassified|living_street|service|pedestrian|footway|path|steps|primary_link|secondary_link|tertiary_link)$' -and $tags.access -notmatch '^(private|no)$' -and $tags.foot -ne 'no') {
        $refs = @($way.nd | ForEach-Object { [string]$_.ref })
        if ($refs.Count -lt 2) { continue }
        $name = if ($tags.name) { $tags.name } elseif ($tags.highway -eq 'steps') { 'Steps' } else { 'Walkway' }
        $name = $name.Replace("`t", ' ').Replace("`n", ' ')
        for ($i = 0; $i -lt $refs.Count - 1; $i++) {
            if ($nodes.ContainsKey($refs[$i]) -and $nodes.ContainsKey($refs[$i+1])) {
                [void]$used.Add($refs[$i]); [void]$used.Add($refs[$i+1])
                $roads += "E`t$($way.id):$i`t$($refs[$i])`t$($refs[$i+1])`t$name`t$($tags.highway)"
            }
        }
    } elseif ($tags.building -or $tags.leisure -in @('park', 'garden') -or $tags.landuse -in @('grass', 'recreation_ground')) {
        $kind = if ($tags.building) { 'building' } else { 'park' }
        $points = @()
        foreach ($nd in $way.nd) { $n = $nodes[[string]$nd.ref]; if ($n) { $points += "$($n.lat),$($n.lon)" } }
        if ($points.Count -gt 2) {
            $label = if ($tags.name) { $tags.name.Replace("`t", ' ').Replace("`n", ' ') } else { '' }
            $areas += "A`t$kind`t$label`t$($points -join ';')"
        }
    }
}
foreach ($id in ($used | Sort-Object)) { $n = $nodes[$id]; $lines.Add("N`t$id`t$($n.lat)`t$($n.lon)") }
$lines.AddRange([string[]]$roads)
$lines.AddRange([string[]]$areas)
if ($roads.Count -lt 100) { throw 'Street download incomplete; preserving previous asset' }
[System.IO.File]::WriteAllLines($output, $lines, [System.Text.UTF8Encoding]::new($false))
Write-Output "Bundled OSM data: $($used.Count) nodes, $($roads.Count) road segments, $($areas.Count) land features."
