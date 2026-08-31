$ErrorActionPreference = 'SilentlyContinue'

$candidates = [System.Collections.Generic.List[string]]::new()
function Add-Candidate([string] $path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return }
    $expanded = [Environment]::ExpandEnvironmentVariables($path.Trim().Trim('"'))
    if (-not $candidates.Contains($expanded)) { $candidates.Add($expanded) }
}

if ($env:MYSQL_HOME) { Add-Candidate (Join-Path $env:MYSQL_HOME 'bin\mysql.exe') }
$fromPath = Get-Command mysql.exe -ErrorAction SilentlyContinue
if ($fromPath) { Add-Candidate $fromPath.Source }

Get-CimInstance Win32_Service | Where-Object {
    $_.Name -match 'mysql|maria' -or $_.DisplayName -match 'mysql|maria'
} | ForEach-Object {
    if ($_.PathName -match '^\s*"([^"]+)"' -or $_.PathName -match '^\s*([^\s]+)') {
        Add-Candidate (Join-Path (Split-Path -Parent $matches[1]) 'mysql.exe')
        Add-Candidate (Join-Path (Split-Path -Parent $matches[1]) 'mariadb.exe')
    }
}

foreach ($root in @(
    (Join-Path $env:ProgramFiles 'MySQL'),
    (Join-Path $env:ProgramFiles 'MariaDB'),
    (Join-Path ${env:ProgramFiles(x86)} 'MySQL'),
    (Join-Path $env:SystemDrive 'xampp\mysql\bin')
)) {
    if (Test-Path -LiteralPath $root) {
        Get-ChildItem -LiteralPath $root -File -Recurse -Depth 4 |
            Where-Object Name -In @('mysql.exe', 'mariadb.exe') |
            ForEach-Object { Add-Candidate $_.FullName }
    }
}

foreach ($candidate in $candidates) {
    if (Test-Path -LiteralPath $candidate) {
        (Resolve-Path -LiteralPath $candidate).Path
        exit 0
    }
}

Write-Error 'mysql.exe or mariadb.exe was not found. Set MYSQL_HOME to the database installation directory.'
exit 1
