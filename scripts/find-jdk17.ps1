$ErrorActionPreference = 'SilentlyContinue'

$candidates = [System.Collections.Generic.List[string]]::new()

function Add-Candidate([string] $path) {
    if ([string]::IsNullOrWhiteSpace($path)) { return }
    $expanded = [Environment]::ExpandEnvironmentVariables($path.Trim().Trim('"'))
    if (-not $candidates.Contains($expanded)) { $candidates.Add($expanded) }
}

Add-Candidate $env:JAVA17_HOME
Add-Candidate $env:JAVA_HOME

foreach ($registryPath in @(
    'HKLM:\SOFTWARE\JavaSoft\JDK',
    'HKLM:\SOFTWARE\WOW6432Node\JavaSoft\JDK',
    'HKLM:\SOFTWARE\Eclipse Adoptium\JDK'
)) {
    Get-ChildItem $registryPath -Recurse | ForEach-Object {
        Add-Candidate (Get-ItemProperty $_.PSPath).JavaHome
    }
}

$javac = Get-Command javac.exe -ErrorAction SilentlyContinue
if ($javac) { Add-Candidate (Split-Path -Parent (Split-Path -Parent $javac.Source)) }

foreach ($root in @(
    (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
    (Join-Path $env:ProgramFiles 'Java'),
    (Join-Path $env:ProgramFiles 'Microsoft'),
    (Join-Path ${env:ProgramFiles(x86)} 'Java')
)) {
    if (Test-Path -LiteralPath $root) {
        Get-ChildItem -LiteralPath $root -Directory -Recurse -Depth 2 | ForEach-Object {
            Add-Candidate $_.FullName
        }
    }
}

foreach ($candidate in $candidates) {
    $java = Join-Path $candidate 'bin\java.exe'
    $compiler = Join-Path $candidate 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $java) -or -not (Test-Path -LiteralPath $compiler)) { continue }
    $versionText = (& $compiler -version 2>&1 | Out-String).Trim()
    if ($versionText -match 'javac\s+17(?:\.|\s|$)') {
        (Resolve-Path -LiteralPath $candidate).Path
        exit 0
    }
}

Write-Error 'A complete JDK 17 installation was not found. Set JAVA17_HOME or JAVA_HOME to a JDK 17 directory.'
exit 1
