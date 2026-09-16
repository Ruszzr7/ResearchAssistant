param(
    [string]$ResultPath = (Join-Path $PSScriptRoot '..\..\backend\target\eval\agent-skill-evaluation-v2.jsonl'),
    [switch]$RegressionFailures,
    [switch]$RegressionFormulaFailures,
    [switch]$RegressionPaper11,
    [switch]$RegressionAnswerFailures,
    [switch]$RegressionAnswerLimit,
    [switch]$Sample20,
    [switch]$ForceFresh,
    [bool]$WaitForClientAction = $true
)

$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18080/api'
$datasetPath = Join-Path $PSScriptRoot '..\..\backend\src\test\resources\eval\agent-skill-300.jsonl'
$rows = Get-Content -LiteralPath $datasetPath -Encoding utf8 | ForEach-Object { $_ | ConvertFrom-Json }

# One case per paper. The distribution is 6 profile+evidence, 6 evidence,
# 5 action, 5 evidence+action, 4 all-three, and 4 no-skill cases.
$selection = @(
    @{ paperId = 2;  caseNumber = 1 },  @{ paperId = 3;  caseNumber = 1 },
    @{ paperId = 5;  caseNumber = 1 },  @{ paperId = 6;  caseNumber = 1 },
    @{ paperId = 8;  caseNumber = 1 },  @{ paperId = 30; caseNumber = 1 },
    @{ paperId = 7;  caseNumber = 3 },  @{ paperId = 10; caseNumber = 3 },
    @{ paperId = 11; caseNumber = 3 },  @{ paperId = 13; caseNumber = 3 },
    @{ paperId = 14; caseNumber = 3 },  @{ paperId = 31; caseNumber = 3 },
    @{ paperId = 17; caseNumber = 10 }, @{ paperId = 23; caseNumber = 10 },
    @{ paperId = 26; caseNumber = 10 }, @{ paperId = 29; caseNumber = 10 },
    @{ paperId = 32; caseNumber = 10 }, @{ paperId = 18; caseNumber = 9 },
    @{ paperId = 21; caseNumber = 9 },  @{ paperId = 22; caseNumber = 9 },
    @{ paperId = 24; caseNumber = 9 },  @{ paperId = 25; caseNumber = 9 },
    @{ paperId = 9;  caseNumber = 10 }, @{ paperId = 12; caseNumber = 10 },
    @{ paperId = 15; caseNumber = 10 }, @{ paperId = 27; caseNumber = 10 },
    @{ paperId = 1;  caseNumber = 10 }, @{ paperId = 4;  caseNumber = 10 },
    @{ paperId = 16; caseNumber = 10 }, @{ paperId = 28; caseNumber = 10 }
)
if ($RegressionFailures) {
    $selection = @(
        @{ paperId = 10; caseNumber = 3 }, @{ paperId = 11; caseNumber = 3 },
        @{ paperId = 13; caseNumber = 3 }, @{ paperId = 31; caseNumber = 3 },
        @{ paperId = 18; caseNumber = 9 }
    )
}
if ($RegressionFormulaFailures) {
    $selection = @(
        @{ paperId = 11; caseNumber = 3 }, @{ paperId = 31; caseNumber = 3 }
    )
}
if ($RegressionPaper11) {
    $selection = @(@{ paperId = 11; caseNumber = 3 })
}
if ($RegressionAnswerFailures) {
    $selection = @(
        @{ paperId = 3; caseNumber = 2 }, @{ paperId = 5; caseNumber = 2 },
        @{ paperId = 7; caseNumber = 4 }
    )
}
if ($RegressionAnswerLimit) {
    $selection = @(@{ paperId = 5; caseNumber = 2 })
}
if ($Sample20) {
    # Additional 20-case sample. These case IDs are disjoint from the original
    # 30-case pilot and cover profile+evidence, evidence, action, and compound
    # requests. Keep this selection stable so the result file is reproducible.
    $selection = @(
        @{ paperId = 1;  caseNumber = 2 }, @{ paperId = 2;  caseNumber = 2 },
        @{ paperId = 3;  caseNumber = 2 }, @{ paperId = 4;  caseNumber = 2 },
        @{ paperId = 5;  caseNumber = 2 }, @{ paperId = 6;  caseNumber = 4 },
        @{ paperId = 7;  caseNumber = 4 }, @{ paperId = 8;  caseNumber = 4 },
        @{ paperId = 9;  caseNumber = 4 }, @{ paperId = 10; caseNumber = 4 },
        @{ paperId = 11; caseNumber = 9 }, @{ paperId = 12; caseNumber = 9 },
        @{ paperId = 13; caseNumber = 9 }, @{ paperId = 14; caseNumber = 9 },
        @{ paperId = 15; caseNumber = 9 }, @{ paperId = 18; caseNumber = 10 },
        @{ paperId = 21; caseNumber = 10 }, @{ paperId = 22; caseNumber = 10 },
        @{ paperId = 24; caseNumber = 10 }, @{ paperId = 25; caseNumber = 10 }
    )
}

# Questions and gold labels are versioned together. Never reuse runs produced
# from the earlier dataset wording.
$existingRuns = @{}

$terminalStatuses = @('COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_USER')

function Invoke-ApiJson {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('Get', 'Post')][string]$Method,
        [Parameter(Mandatory = $true)][string]$Uri,
        [object]$Body
    )
    if ($Method -eq 'Get') {
        $response = Invoke-WebRequest -Method Get -Uri $Uri -UseBasicParsing -TimeoutSec 30
    } else {
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $response = Invoke-WebRequest -Method Post -Uri $Uri -ContentType 'application/json; charset=utf-8' -Body $bytes -UseBasicParsing -TimeoutSec 30
    }
    $raw = $response.RawContentStream.ToArray()
    return [Text.Encoding]::UTF8.GetString($raw) | ConvertFrom-Json
}

function Get-RunSnapshot {
    param([Parameter(Mandatory = $true)][string]$RunId)
    return Invoke-ApiJson -Method Get -Uri "$base/agent/turns/runs/$RunId"
}

function Get-RunEvents {
    param([Parameter(Mandatory = $true)][string]$RunId)
    return Invoke-ApiJson -Method Get -Uri "$base/agent/turns/runs/$RunId/events?after=0"
}

function New-ResultRecord {
    param(
        [Parameter(Mandatory = $true)]$Row,
        [Parameter(Mandatory = $true)][long]$SessionId,
        [Parameter(Mandatory = $true)][string]$RunId,
        [Parameter(Mandatory = $true)]$Snapshot,
        [Parameter(Mandatory = $true)]$Events,
        [Parameter(Mandatory = $true)][int]$Polls,
        [bool]$RequestValid = $true,
        [string]$RequestError = ''
    )
    $eventList = @($Events.data)
    $activationEvents = @($eventList | Where-Object {
        $_.type -eq 'tool.completed' -and $_.data.toolName -eq 'activate_skill' -and
        -not [string]::IsNullOrWhiteSpace([string]$_.data.skillName)
    })
    $predicted = @($activationEvents | ForEach-Object { [string]$_.data.skillName } | Select-Object -Unique)
    $required = @($Row.requiredSkills | ForEach-Object { [string]$_ })
    $allowed = @($Row.allowedSkills | ForEach-Object { [string]$_ })
    $toolNames = @($eventList | Where-Object { $_.data.toolName } | ForEach-Object { [string]$_.data.toolName })
    $eventTypes = @($eventList | ForEach-Object { [string]$_.type })
    $status = [string]$Snapshot.data.status
    $message = [string]$Snapshot.data.message
    $pendingCount = @($Snapshot.data.pendingActions).Count
    $failedEvent = @($eventList | Where-Object { $_.type -eq 'run.failed' } | Select-Object -Last 1)
    $failureData = if ($failedEvent.Count -gt 0) { $failedEvent[0].data } else { $null }
    $failureCode = if ($failureData.inferredErrorCode) {
        [string]$failureData.inferredErrorCode
    } elseif ($failureData.errorCode) {
        [string]$failureData.errorCode
    } else { '' }
    $failureCategory = if ($failureData.failureCategory) { [string]$failureData.failureCategory } else { '' }
    $retryable = if ($null -ne $failureData -and $failureData.PSObject.Properties.Name -contains 'retryable') {
        [bool]$failureData.retryable
    } else { $false }
    $diagnostics = @($eventList | Where-Object { $_.type -eq 'run.diagnostics' } | Select-Object -Last 1)
    $providerInvoked = $false
    $failureProviderInvoked = $false
    if ($diagnostics.Count -gt 0 -and $diagnostics[0].data.modelCalls) {
        $modelCalls = @($diagnostics[0].data.modelCalls)
        foreach ($call in $modelCalls) {
            if ([string]$call.responseKind -in @('TOOL_CALL', 'TEXT', 'EMPTY', 'ERROR')) {
                $providerInvoked = $true
            }
        }
        $lastFailedCall = @($modelCalls | Where-Object { [string]$_.status -eq 'FAILED' } | Select-Object -Last 1)
        if ($lastFailedCall.Count -gt 0) {
            $failureProviderInvoked = [string]$lastFailedCall[0].responseKind -ne 'NOT_SENT'
        }
    }
    # Skill routing can be scored once activation events exist, but an action run
    # is not complete until the browser submits its execution receipt.
    $metricEligible = $RequestValid -and ($status -in @('COMPLETED', 'WAITING_USER', 'WAITING_CLIENT'))
    $businessComplete = $status -in @('COMPLETED', 'WAITING_USER')
    $actionRequested = $toolNames -contains 'paper_action'
    $actionExecutionStatus = if ($status -eq 'WAITING_CLIENT') { 'PENDING_CLIENT_RECEIPT' }
        elseif ($status -eq 'COMPLETED' -and $actionRequested) { 'RECEIPT_RECORDED' }
        elseif ($status -eq 'WAITING_USER') { 'NOT_EXECUTED_CLARIFICATION' }
        else { 'NOT_APPLICABLE' }
    $exclusionReason = if ($RequestError) { $RequestError } elseif ($metricEligible) { '' } elseif ($failureCode) { $failureCode } else { $status }
    return [ordered]@{
        caseId = [string]$Row.caseId
        paperId = [int]$Row.paperId
        paperTitle = [string]$Row.paperTitle
        caseNumber = [int]$Row.caseNumber
        caseType = [string]$Row.caseType
        # goldSkills remains an alias so older result readers still work.
        goldSkills = $required
        requiredSkills = $required
        allowedSkills = $allowed
        predictedSkills = $predicted
        status = $status
        sessionId = $SessionId
        runId = $RunId
        pollCount = $Polls
        pendingActionCount = $pendingCount
        citationCount = @($Snapshot.data.citations).Count
        evidenceCount = @($Snapshot.data.evidence).Count
        toolNames = @($toolNames | Select-Object -Unique)
        eventTypes = @($eventTypes | Select-Object -Unique)
        message = $message
        failureCode = $failureCode
        failureCategory = $failureCategory
        retryable = $retryable
        providerInvoked = $providerInvoked
        failureProviderInvoked = $failureProviderInvoked
        requestValid = $RequestValid
        requestError = $RequestError
        metricEligible = $metricEligible
        businessComplete = $businessComplete
        actionExecutionStatus = $actionExecutionStatus
        exclusionReason = $exclusionReason
    }
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ResultPath) | Out-Null
$known = @{}
if (Test-Path -LiteralPath $ResultPath) {
    foreach ($line in @(Get-Content -LiteralPath $ResultPath -Encoding utf8)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $record = $line | ConvertFrom-Json
            if ($record.caseId) { $known[[string]$record.caseId] = $true }
        } catch { }
    }
}

$total = $selection.Count
$index = 0
foreach ($item in $selection) {
    $index++
    $row = $rows | Where-Object { $_.paperId -eq $item.paperId -and $_.caseNumber -eq $item.caseNumber } | Select-Object -First 1
    if ($null -eq $row) { throw "dataset row not found: paper $($item.paperId), case $($item.caseNumber)" }
    $caseId = [string]$row.caseId
    if ($known.ContainsKey($caseId)) {
        Write-Output ("[{0}/{1}] {2} already recorded" -f $index, $total, $caseId)
        continue
    }

    $sessionId = 0L
    $runId = $null
    $requestValid = $true
    $requestError = ''
    if (-not $ForceFresh -and $existingRuns.ContainsKey($caseId)) {
        $sessionId = [long]$existingRuns[$caseId].sessionId
        $runId = [string]$existingRuns[$caseId].runId
    } else {
        $sessionBody = [ordered]@{
            paperIds = @([int]$row.paperId)
            primaryPaperId = [int]$row.paperId
            title = "agent-skill-pilot-$caseId"
            mode = 'analysis'
            outputLanguage = 'ZH'
        }
        $sessionResponse = Invoke-ApiJson -Method Post -Uri "$base/research/sessions" -Body $sessionBody
        $sessionId = [long]$sessionResponse.data.id
        $turnBody = [ordered]@{
            conversationId = $sessionId
            primaryPaperId = [int]$row.paperId
            userMessage = [string]$row.question
            explicitAction = $null
            selectedContent = $null
            attachmentIds = @()
            formulaAttachmentIds = @()
            uiContext = $null
            clientRequestId = "pilot-20260914-$caseId"
            resumeRunId = $null
        }
        $turnResponse = Invoke-ApiJson -Method Post -Uri "$base/agent/turns" -Body $turnBody
        $runId = [string]$turnResponse.data.runId
        # Verify the exact UTF-8 question persisted before accepting this run.
        # A PowerShell encoding regression must never become a Skill metric.
        try {
            $sessionCheck = Invoke-ApiJson -Method Get -Uri "$base/research/sessions/$sessionId"
            $persisted = @($sessionCheck.data.messages | Where-Object {
                $_.role -eq 'USER' -and $_.messageKey -eq "agent-user-pilot-20260914-$caseId"
            } | Select-Object -Last 1)
            if ($persisted.Count -eq 0 -or [string]$persisted[0].content -ne [string]$row.question) {
                $requestValid = $false
                $requestError = 'REQUEST_HARNESS_ENCODING'
            }
        } catch {
            $requestValid = $false
            $requestError = 'REQUEST_HARNESS_VERIFICATION_FAILED'
        }
    }

    if (-not $requestValid) {
        try { Invoke-ApiJson -Method Post -Uri "$base/agent/turns/runs/$runId/cancel" -Body @{} | Out-Null } catch { }
        $snapshot = Get-RunSnapshot -RunId $runId
        $eventResponse = Get-RunEvents -RunId $runId
        $record = New-ResultRecord -Row $row -SessionId $sessionId -RunId $runId -Snapshot $snapshot -Events $eventResponse -Polls 0 -RequestValid $false -RequestError $requestError
        $record.status = 'HARNESS_ERROR'
        $record.failureCode = $requestError
        $record.failureCategory = 'REQUEST_HARNESS'
        $record.retryable = $false
        $record.metricEligible = $false
        $record.exclusionReason = $requestError
        ($record | ConvertTo-Json -Depth 20 -Compress) | Add-Content -LiteralPath $ResultPath -Encoding utf8
        Write-Output ("[{0}/{1}] {2} paper={3} status=HARNESS_ERROR reason={4}" -f $index, $total, $caseId, $record.paperId, $requestError)
        continue
    }

    $snapshot = $null
    $polls = 0
    $timedOut = $false
    $waitingClientReported = $false
    # 480 × 2 seconds matches the 960-second foreground watcher and outlasts
    # the backend's 900-second durable deadline.
    while ($polls -lt 480) {
        $snapshot = Get-RunSnapshot -RunId $runId
        $polls++
        $status = [string]$snapshot.data.status
        if ($terminalStatuses -contains $status) { break }
        if ($status -eq 'WAITING_CLIENT' -and -not $WaitForClientAction) { break }
        if ($status -eq 'WAITING_CLIENT' -and -not $waitingClientReported) {
            Write-Output ("[{0}/{1}] {2} waiting for the open paper page to submit the action receipt" -f $index, $total, $caseId)
            $waitingClientReported = $true
        }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $snapshot) { throw "run status unavailable: $caseId" }
    if (($terminalStatuses -notcontains [string]$snapshot.data.status) -and
            -not ([string]$snapshot.data.status -eq 'WAITING_CLIENT' -and -not $WaitForClientAction)) {
        $timedOut = $true
        try { Invoke-ApiJson -Method Post -Uri "$base/agent/turns/runs/$runId/cancel" -Body @{} | Out-Null } catch { }
        $snapshot = Get-RunSnapshot -RunId $runId
    }
    $eventResponse = Get-RunEvents -RunId $runId
    $record = New-ResultRecord -Row $row -SessionId $sessionId -RunId $runId -Snapshot $snapshot -Events $eventResponse -Polls $polls
    if ($timedOut) { $record.status = 'TIMEOUT' }
    ($record | ConvertTo-Json -Depth 20 -Compress) | Add-Content -LiteralPath $ResultPath -Encoding utf8
    $requiredText = (@($record.requiredSkills) -join ',')
    $allowedText = (@($record.allowedSkills) -join ',')
    $predictedText = (@($record.predictedSkills) -join ',')
    Write-Output ("[{0}/{1}] {2} paper={3} status={4} required=[{5}] allowed=[{6}] predicted=[{7}]" -f $index, $total, $caseId, $record.paperId, $record.status, $requiredText, $allowedText, $predictedText)
}

Write-Output "pilot result file: $ResultPath"
