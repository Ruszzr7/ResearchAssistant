param(
    [Parameter(Mandatory = $true)]
    [string]$CasesFile,
    [string]$BaseUrl = "http://127.0.0.1:8080",
    [int]$PollIntervalSeconds = 2,
    [int]$CaseTimeoutSeconds = 240,
    [string[]]$CaseIds = @(),
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Invoke-Api {
    param([string]$Method, [string]$Path, $Body = $null, [hashtable]$Headers = @{})
    $parameters = @{
        Method = $Method
        Uri = "$BaseUrl$Path"
        Headers = $Headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json; charset=utf-8"
        $parameters.Body = $Body | ConvertTo-Json -Depth 20 -Compress
    }
    $response = Invoke-WebRequest @parameters -UseBasicParsing
    $response.RawContentStream.Position = 0
    $reader = New-Object IO.StreamReader($response.RawContentStream, [Text.Encoding]::UTF8, $true)
    try { $envelope = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($envelope.code -ne 200) { throw "API $Path failed: $($envelope.message)" }
    return $envelope.data
}

function New-EvalSession {
    param([long]$PaperId, [string]$BatchId, [string]$SessionKey)
    return Invoke-Api -Method POST -Path "/api/research/sessions" -Body @{
        paperIds = @($PaperId)
        primaryPaperId = $PaperId
        title = "[EVAL $BatchId] $SessionKey"
        mode = "PAPER"
        lastPage = 1
        outputLanguage = "zh"
    }
}

function Wait-AgentRun {
    param([string]$RunId, [int]$TimeoutSeconds)
    $started = [DateTimeOffset]::UtcNow
    $terminal = @("COMPLETED", "FAILED", "CANCELLED", "WAITING_USER", "WAITING_CLIENT")
    while ($true) {
        $state = Invoke-Api -Method GET -Path "/api/agent/turns/runs/$RunId"
        if ($terminal -contains $state.status) {
            return @{ State = $state; ElapsedMs = [int]([DateTimeOffset]::UtcNow - $started).TotalMilliseconds }
        }
        if (([DateTimeOffset]::UtcNow - $started).TotalSeconds -ge $TimeoutSeconds) {
            throw "run $RunId did not finish within $TimeoutSeconds seconds"
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
}

function Contains-Any {
    param([string]$Text, $Terms)
    foreach ($term in @($Terms)) {
        if ($Text.IndexOf([string]$term, [StringComparison]::OrdinalIgnoreCase) -ge 0) { return $true }
    }
    return $false
}

function Select-Evidence {
    param($StoredResult, [string]$Formula)
    $evidence = @($StoredResult.state.evidence)
    if ($Formula) {
        $matched = $evidence | Where-Object { $_.formulaNumber -eq $Formula } | Select-Object -First 1
        if ($null -ne $matched) { return $matched }
    }
    return $evidence | Select-Object -First 1
}

$resolvedCases = (Resolve-Path -LiteralPath $CasesFile).Path
$suite = Get-Content -LiteralPath $resolvedCases -Raw -Encoding utf8 | ConvertFrom-Json
if ($suite.schemaVersion -ne "agent-live-eval-v1") { throw "unsupported eval schema" }

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $projectRoot = Split-Path -Parent $PSScriptRoot
    $OutputDirectory = Join-Path $projectRoot "tmp\agent-eval"
}
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null

$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
if ($health.status -ne "UP") { throw "backend is not healthy" }
$readiness = Invoke-Api -Method GET -Path "/api/papers/$($suite.paperId)/readiness"
if (-not $readiness.conversationReady) {
    throw "paper $($suite.paperId) is not conversation-ready: $($readiness.status)"
}
$artifact = Invoke-Api -Method GET -Path "/api/papers/$($suite.paperId)/layout-artifact"

$sessions = @{}
$caseResults = @{}
$results = @()
$casesToRun = if ($CaseIds.Count -eq 0) { @($suite.cases) } else {
    @($suite.cases | Where-Object { $CaseIds -contains [string]$_.id })
}
if ($casesToRun.Count -eq 0) { throw "no eval cases matched CaseIds" }

foreach ($case in $casesToRun) {
    if (-not $sessions.ContainsKey($case.sessionKey)) {
        $sessions[$case.sessionKey] = New-EvalSession -PaperId $suite.paperId -BatchId $suite.batchId -SessionKey $case.sessionKey
    }
    $session = $sessions[$case.sessionKey]
    $payload = @{
        conversationId = $session.id
        primaryPaperId = $suite.paperId
        userMessage = $case.message
        clientRequestId = "eval-$($suite.batchId)-$($case.id)-$([guid]::NewGuid().ToString('N'))"
        uiContext = @{ pageNumber = 1; zoom = 1.0; activeTool = "SELECT" }
    }

    $setupErrors = New-Object System.Collections.Generic.List[string]
    if ($case.selectionFromCase) {
        $sourceCase = $caseResults[[string]$case.selectionFromCase]
        if ($null -eq $sourceCase) {
            $setupErrors.Add("selection source case was not executed")
        } else {
            $selected = Select-Evidence -StoredResult $sourceCase -Formula ([string]$case.selectionFormula)
            if ($null -eq $selected) {
                $setupErrors.Add("selection source case returned no matching evidence")
            } else {
                $payload.selectedContent = @{
                    selectionId = "eval-selection-$($case.id)"
                    paperId = $suite.paperId
                    documentHash = $artifact.documentHash
                    pageNumber = $selected.locators[0].pageNumber
                    contentType = $(if ($selected.formulaNumber) { "FORMULA" } else { "TEXT" })
                    exactText = $selected.quote
                    sourceObjectIds = @($selected.sourceObjectId)
                }
            }
        }
    }

    $submission = $null
    $waited = $null
    $events = @()
    $executionError = $null
    if ($setupErrors.Count -eq 0) {
        try {
            $submission = Invoke-Api -Method POST -Path "/api/agent/turns" -Body $payload
            $waited = Wait-AgentRun -RunId $submission.runId -TimeoutSeconds $CaseTimeoutSeconds
            $events = @(Invoke-Api -Method GET -Path "/api/agent/turns/runs/$($submission.runId)/events")
        } catch {
            $executionError = $_.Exception.Message
        }
    }

    $failures = New-Object System.Collections.Generic.List[string]
    foreach ($errorText in $setupErrors) { $failures.Add($errorText) }
    if ($executionError) { $failures.Add("execution error: $executionError") }
    $state = if ($null -ne $waited) { $waited.State } else { $null }
    $toolNames = @($events | Where-Object { $_.data.toolName } | ForEach-Object { [string]$_.data.toolName })
    $toolEvents = @($events | Where-Object { $_.data.toolName })
    $evidenceToolEvents = @($toolEvents | Where-Object { $_.data.toolName -eq "retrieve_paper_evidence" })
    $actualEvidenceSearches = @($evidenceToolEvents | Where-Object { $_.data.reused -ne $true }).Count
    $failedToolCalls = @($toolEvents | Where-Object { $_.type -eq "tool.failed" }).Count
    if ($null -ne $state) {
        if (@($case.expectedStatuses) -notcontains $state.status) {
            $failures.Add("status $($state.status) not in expected statuses")
        }
        foreach ($tool in @($case.requiredTools)) {
            if ($tool -and $toolNames -notcontains $tool) { $failures.Add("required tool missing: $tool") }
        }
        foreach ($tool in @($case.forbiddenTools)) {
            if ($tool -and $toolNames -contains $tool) { $failures.Add("forbidden tool called: $tool") }
        }
        if ($null -ne $case.maxToolCalls -and $toolNames.Count -gt [int]$case.maxToolCalls) {
            $failures.Add("tool calls $($toolNames.Count) exceeded $($case.maxToolCalls)")
        }
        if ($null -ne $case.maxEvidenceToolCalls) {
            $evidenceToolCalls = @($toolNames | Where-Object { $_ -eq "retrieve_paper_evidence" }).Count
            if ($evidenceToolCalls -gt [int]$case.maxEvidenceToolCalls) {
                $failures.Add("evidence tool calls $evidenceToolCalls exceeded $($case.maxEvidenceToolCalls)")
            }
        }
        if ($null -ne $case.maxActualEvidenceSearches -and $actualEvidenceSearches -gt [int]$case.maxActualEvidenceSearches) {
            $failures.Add("actual evidence searches $actualEvidenceSearches exceeded $($case.maxActualEvidenceSearches)")
        }
        if ($null -ne $case.maxFailedToolCalls -and $failedToolCalls -gt [int]$case.maxFailedToolCalls) {
            $failures.Add("failed tool calls $failedToolCalls exceeded $($case.maxFailedToolCalls)")
        }
        if ($null -ne $case.maxElapsedMs -and $waited.ElapsedMs -gt [int]$case.maxElapsedMs) {
            $failures.Add("elapsed time $($waited.ElapsedMs) ms exceeded $($case.maxElapsedMs) ms")
        }
        if ($null -ne $case.maxEvidenceToolDurationMs) {
            foreach ($event in $evidenceToolEvents) {
                if ($null -ne $event.data.durationMs -and [long]$event.data.durationMs -gt [long]$case.maxEvidenceToolDurationMs) {
                    $failures.Add("evidence tool duration $($event.data.durationMs) ms exceeded $($case.maxEvidenceToolDurationMs) ms")
                }
            }
        }
        if ($null -ne $case.maxEvidenceResponseBytes) {
            foreach ($event in $evidenceToolEvents) {
                if ($null -ne $event.data.responseBytes -and [long]$event.data.responseBytes -gt [long]$case.maxEvidenceResponseBytes) {
                    $failures.Add("evidence response $($event.data.responseBytes) bytes exceeded $($case.maxEvidenceResponseBytes) bytes")
                }
            }
        }
        if ($case.requireAllEvidenceNeedsFound) {
            $actual = $evidenceToolEvents | Where-Object { $_.data.reused -ne $true } | Select-Object -First 1
            if ($null -eq $actual -or $null -eq $actual.data.evidenceNeedCount) {
                $failures.Add("actual evidence batch diagnostics missing")
            } elseif ([int]$actual.data.evidenceNeedFoundCount -ne [int]$actual.data.evidenceNeedCount) {
                $failures.Add("evidence needs found $($actual.data.evidenceNeedFoundCount)/$($actual.data.evidenceNeedCount)")
            }
        }
        $answer = [string]$state.message
        foreach ($group in @($case.requiredConceptGroups)) {
            if (-not (Contains-Any -Text $answer -Terms $group)) {
                $failures.Add("answer missing concept group: $($group -join ' | ')")
            }
        }
        foreach ($term in @($case.forbiddenTerms)) {
            if ($term -and (Contains-Any -Text $answer -Terms @($term))) { $failures.Add("answer contains forbidden term: $term") }
        }
        if ($null -ne $case.minEvidence -and @($state.evidence).Count -lt [int]$case.minEvidence) {
            $failures.Add("evidence count $(@($state.evidence).Count) below $($case.minEvidence)")
        }
        if ($null -ne $case.maxEvidence -and @($state.evidence).Count -gt [int]$case.maxEvidence) {
            $failures.Add("evidence count $(@($state.evidence).Count) exceeded $($case.maxEvidence)")
        }
        $evidenceText = (@($state.evidence | ForEach-Object {
            @([string]$_.quote) + @($_.locators | ForEach-Object { [string]$_.targetText })
        }) -join " ")
        foreach ($group in @($case.evidenceRequiredTermGroups)) {
            if (-not (Contains-Any -Text $evidenceText -Terms $group)) {
                $failures.Add("evidence missing term group: $($group -join ' | ')")
            }
        }
        if (($case.PSObject.Properties.Name -contains "evidencePageAny") -and @($case.evidencePageAny).Count -gt 0) {
            $evidencePages = @($state.evidence | ForEach-Object { @($_.locators) } | ForEach-Object { [int]$_.pageNumber })
            if (-not (@($case.evidencePageAny) | Where-Object { $evidencePages -contains [int]$_ })) {
                $failures.Add("expected evidence page not found")
            }
        }
        if (($case.PSObject.Properties.Name -contains "formulaEvidenceAny") -and @($case.formulaEvidenceAny).Count -gt 0) {
            $formulas = @($state.evidence | ForEach-Object { [string]$_.formulaNumber })
            if (-not (@($case.formulaEvidenceAny) | Where-Object { $formulas -contains [string]$_ })) {
                $failures.Add("expected formula evidence not found")
            }
        }
        if ($case.requireEvidenceIntegrity -and @($state.evidence).Count -gt 0) {
            $returnedIds = @($evidenceToolEvents |
                Where-Object { $_.data.reused -ne $true } |
                ForEach-Object { @($_.data.returnedSourceObjectIds) })
            foreach ($evidence in @($state.evidence)) {
                if (-not $evidence.sourceObjectId -or $returnedIds -notcontains [string]$evidence.sourceObjectId) {
                    $failures.Add("submitted evidence was not returned by the actual evidence batch: $($evidence.sourceObjectId)")
                }
            }
        }
        if ($case.pendingActionType) {
            $actions = @($state.pendingActions)
            if ($actions.Count -eq 0 -or $actions[0].actionType -ne $case.pendingActionType) {
                $failures.Add("pending action $($case.pendingActionType) not found")
            }
        }
    }

    $record = [ordered]@{
        id = $case.id
        category = $case.category
        sessionId = $session.id
        runId = if ($null -ne $submission) { $submission.runId } else { $null }
        status = if ($null -ne $state) { $state.status } else { "NOT_RUN" }
        passed = $failures.Count -eq 0
        failures = @($failures)
        elapsedMs = if ($null -ne $waited) { $waited.ElapsedMs } else { $null }
        tools = $toolNames
        toolCallCount = $toolNames.Count
        failedToolCallCount = $failedToolCalls
        evidenceToolCallCount = $evidenceToolEvents.Count
        actualEvidenceSearchCount = $actualEvidenceSearches
        evidenceToolDurationMs = @($evidenceToolEvents | ForEach-Object { $_.data.durationMs } | Where-Object { $null -ne $_ })
        evidenceResponseBytes = @($evidenceToolEvents | ForEach-Object { $_.data.responseBytes } | Where-Object { $null -ne $_ })
        evidenceCount = if ($null -ne $state) { @($state.evidence).Count } else { 0 }
        formulaEvidence = if ($null -ne $state) { @($state.evidence | ForEach-Object { $_.formulaNumber } | Where-Object { $_ }) } else { @() }
        answer = if ($null -ne $state) { $state.message } else { $null }
        pendingActions = if ($null -ne $state) { @($state.pendingActions) } else { @() }
    }
    $results += [pscustomobject]$record
    if ($null -ne $state) { $caseResults[$case.id] = @{ state = $state } }
}

$passed = @($results | Where-Object passed).Count
$durations = @($results | Where-Object { $null -ne $_.elapsedMs } | ForEach-Object { [int]$_.elapsedMs } | Sort-Object)
$p50 = if ($durations.Count -eq 0) { $null } else { $durations[[Math]::Floor(($durations.Count - 1) * 0.50)] }
$p95 = if ($durations.Count -eq 0) { $null } else { $durations[[Math]::Floor(($durations.Count - 1) * 0.95)] }
$report = [ordered]@{
    schemaVersion = "agent-live-eval-report-v1"
    batchId = $suite.batchId
    paperId = $suite.paperId
    paperTitle = $suite.paperTitle
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    summary = [ordered]@{
        total = $results.Count
        passed = $passed
        failed = $results.Count - $passed
        passRate = if ($results.Count -eq 0) { 0 } else { [Math]::Round($passed / $results.Count, 4) }
        latencyP50Ms = $p50
        latencyP95Ms = $p95
        toolCalls = ($results | Measure-Object -Property toolCallCount -Sum).Sum
        actualEvidenceSearches = ($results | Measure-Object -Property actualEvidenceSearchCount -Sum).Sum
        failedToolCalls = ($results | Measure-Object -Property failedToolCallCount -Sum).Sum
    }
    results = $results
}
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$jsonPath = Join-Path $OutputDirectory "$($suite.batchId)-$stamp.json"
$json = $report | ConvertTo-Json -Depth 30
[IO.File]::WriteAllText($jsonPath, $json, $utf8NoBom)

$markdown = New-Object System.Collections.Generic.List[string]
$markdown.Add("# $($suite.batchId)")
$markdown.Add("")
$markdown.Add("- Paper: $($suite.paperTitle)")
$markdown.Add("- Passed: $passed/$($results.Count)")
$markdown.Add("- Latency p50/p95: $p50 ms / $p95 ms")
$markdown.Add("- Tool calls / actual evidence searches / failed tools: $($report.summary.toolCalls) / $($report.summary.actualEvidenceSearches) / $($report.summary.failedToolCalls)")
$markdown.Add("")
$markdown.Add("| Case | Category | Status | Result | Latency | Tool calls | Actual searches | Evidence |")
$markdown.Add("|---|---|---|---:|---:|---:|---:|---:|")
foreach ($item in $results) {
    $markdown.Add("| $($item.id) | $($item.category) | $($item.status) | $(if ($item.passed) { 'PASS' } else { 'FAIL' }) | $($item.elapsedMs) ms | $($item.toolCallCount) | $($item.actualEvidenceSearchCount) | $($item.evidenceCount) |")
}
$markdownPath = Join-Path $OutputDirectory "$($suite.batchId)-$stamp.md"
[IO.File]::WriteAllLines($markdownPath, $markdown, $utf8NoBom)

Write-Output "REPORT_JSON=$jsonPath"
Write-Output "REPORT_MD=$markdownPath"
Write-Output "PASSED=$passed/$($results.Count)"
