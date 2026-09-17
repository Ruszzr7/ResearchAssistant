param(
    [string]$ResultPath = (Join-Path $PSScriptRoot '..\..\backend\target\eval\agent-skill-evaluation-v2.jsonl'),
    [switch]$RegressionFailures,
    [switch]$RegressionFormulaFailures,
    [switch]$RegressionPaper11,
    [switch]$RegressionAnswerFailures,
    [switch]$RegressionAnswerLimit,
    [switch]$Sample20,
    [ValidateSet(1, 2, 3)][int]$Batch = 0,
    [switch]$ForceFresh,
    [switch]$NoWaitForClientAction,
    [bool]$WaitForClientAction = $true,
    [string[]]$ReplaceCaseIds = @()
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
if ($Batch -gt 0) {
    $paperIds = @($rows | ForEach-Object { [int]$_.paperId } | Sort-Object -Unique)
    $batchPaperIds = @($paperIds | Select-Object -Skip (($Batch - 1) * 10) -First 10)
    if ($batchPaperIds.Count -ne 10) {
        throw "batch $Batch requires 10 papers, but the dataset contains only $($batchPaperIds.Count) in that range"
    }
    $selection = @(
        foreach ($paperId in $batchPaperIds) {
            foreach ($caseNumber in 1..10) {
                @{ paperId = $paperId; caseNumber = $caseNumber }
            }
        }
    )
}

# Explicit replacements are also added to the selection, so a corrected case
# can be rerun directly (for example, -ReplaceCaseIds paper-10-case-08)
# without having to choose a batch that contains it.
foreach ($replaceCaseId in @($ReplaceCaseIds)) {
    if ([string]::IsNullOrWhiteSpace([string]$replaceCaseId)) { continue }
    $replaceRow = $rows | Where-Object { [string]$_.caseId -eq [string]$replaceCaseId.Trim() } | Select-Object -First 1
    if ($null -eq $replaceRow) { throw "dataset case not found: $replaceCaseId" }
    $alreadySelected = @($selection | Where-Object {
        [int]$_.paperId -eq [int]$replaceRow.paperId -and [int]$_.caseNumber -eq [int]$replaceRow.caseNumber
    }).Count -gt 0
    if (-not $alreadySelected) {
        $selection += @{ paperId = [int]$replaceRow.paperId; caseNumber = [int]$replaceRow.caseNumber }
    }
    if ([int]$replaceRow.caseNumber -eq 8) {
        $locateRow = $rows | Where-Object {
            [int]$_.paperId -eq [int]$replaceRow.paperId -and [int]$_.caseNumber -eq 9
        } | Select-Object -First 1
        if ($null -ne $locateRow) {
            $locateSelected = @($selection | Where-Object {
                [int]$_.paperId -eq [int]$locateRow.paperId -and [int]$_.caseNumber -eq 9
            }).Count -gt 0
            if (-not $locateSelected) {
                $selection += @{ paperId = [int]$locateRow.paperId; caseNumber = 9 }
            }
        }
    }
}

# A trusted-selection case must run after its same-paper locate case once so
# the harness can reuse a real sourceObjectId.  This only changes execution
# order; result records keep their own caseId and are written independently.
$selectionSortProperties = @(
    @{ Expression = { [int]$_.paperId } }
    @{ Expression = {
        switch ([int]$_.caseNumber) {
            9 { 0; break }
            8 { 1; break }
            default { 2 }
        }
    } }
    @{ Expression = { [int]$_.caseNumber } }
)
$selection = @($selection | Sort-Object -Property $selectionSortProperties)

# Questions and gold labels are versioned together. Never reuse runs produced
# from the earlier dataset wording.
$existingRuns = @{}

$terminalStatuses = @('COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_USER')
$waitForClientAction = $WaitForClientAction -and -not $NoWaitForClientAction

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

function Get-TrustedSelectionContext {
    param([Parameter(Mandatory = $true)]$Row)

    # The JSONL fixture deliberately describes the semantic selection, but its
    # fixture-pN-selection ID is not a catalog object.  Resolve the real target
    # from the same paper's locate case, whose action ticket contains the
    # version-bound source ID, page and exact target text.
    $case9Id = "paper-{0:00}-case-09" -f ([int]$Row.paperId)
    $case9 = $knownRecords[$case9Id]
    if ($null -eq $case9 -or [string]::IsNullOrWhiteSpace([string]$case9.runId)) {
        return $null
    }
    try {
        $snapshot = Get-RunSnapshot -RunId ([string]$case9.runId)
        $pending = @($snapshot.data.pendingActions)
        $action = $pending | Where-Object {
            -not [string]::IsNullOrWhiteSpace([string]$_.target.sourceObjectId)
        } | Select-Object -First 1
        if ($null -eq $action) {
            $events = Get-RunEvents -RunId ([string]$case9.runId)
            $required = @($events.data | Where-Object { $_.type -eq 'action.required' } | Select-Object -Last 1)
            if ($required.Count -gt 0) {
                $action = @($required[0].data.pendingActions) | Where-Object {
                    -not [string]::IsNullOrWhiteSpace([string]$_.target.sourceObjectId)
                } | Select-Object -First 1
            }
        }
        if ($null -eq $action) { return $null }
        $target = $action.target
        return [ordered]@{
            sourceObjectId = [string]$target.sourceObjectId
            pageNumber = [int]$target.pageNumber
            targetText = [string]$target.targetText
            precision = [string]$target.precision
            documentHash = [string]$target.documentHash
        }
    } catch {
        return $null
    }
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

function Write-ResultRecord {
    param(
        [Parameter(Mandatory = $true)][string]$CaseId,
        [Parameter(Mandatory = $true)]$Record
    )
    $line = $Record | ConvertTo-Json -Depth 20 -Compress
    $kept = @()
    if (Test-Path -LiteralPath $ResultPath) {
        foreach ($existingLine in @(Get-Content -LiteralPath $ResultPath -Encoding utf8)) {
            if ([string]::IsNullOrWhiteSpace($existingLine)) { continue }
            try {
                $existingRecord = $existingLine | ConvertFrom-Json
                if ([string]$existingRecord.caseId -eq $CaseId) { continue }
            } catch {
                # Preserve an unrelated malformed line; it is outside this
                # case's replacement scope and remains visible for diagnosis.
            }
            $kept += $existingLine
        }
    }
    $kept += $line
    $tempPath = "$ResultPath.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $kept | Set-Content -LiteralPath $tempPath -Encoding utf8
        Move-Item -LiteralPath $tempPath -Destination $ResultPath -Force
    } finally {
        if (Test-Path -LiteralPath $tempPath) {
            Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
        }
    }
    $knownRecords[$CaseId] = $Record
    $known[$CaseId] = $true
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ResultPath) | Out-Null
$known = @{}
$knownRecords = @{}
if (Test-Path -LiteralPath $ResultPath) {
    foreach ($line in @(Get-Content -LiteralPath $ResultPath -Encoding utf8)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        try {
            $record = $line | ConvertFrom-Json
            if ($record.caseId) {
                $known[[string]$record.caseId] = $true
                $knownRecords[[string]$record.caseId] = $record
            }
        } catch { }
    }
}
$replaceSet = @{}
foreach ($replaceCaseId in @($ReplaceCaseIds)) {
    if (-not [string]::IsNullOrWhiteSpace([string]$replaceCaseId)) {
        $replaceSet[[string]$replaceCaseId.Trim()] = $true
    }
}

$total = $selection.Count
$index = 0
foreach ($item in $selection) {
    $index++
    $row = $rows | Where-Object { $_.paperId -eq $item.paperId -and $_.caseNumber -eq $item.caseNumber } | Select-Object -First 1
    if ($null -eq $row) { throw "dataset row not found: paper $($item.paperId), case $($item.caseNumber)" }
    $caseId = [string]$row.caseId
    if ($known.ContainsKey($caseId) -and -not $replaceSet.ContainsKey($caseId)) {
        Write-Output ("[{0}/{1}] {2} already recorded" -f $index, $total, $caseId)
        continue
    }

    $sessionId = 0L
    $runId = $null
    $requestValid = $true
    $requestError = ''
    if (-not $ForceFresh -and -not $replaceSet.ContainsKey($caseId) -and $existingRuns.ContainsKey($caseId)) {
        $sessionId = [long]$existingRuns[$caseId].sessionId
        $runId = [string]$existingRuns[$caseId].runId
    } else {
        $selectedContent = $null
        $uiContext = $null
        if ($row.context -and $row.context.requiresLiveSelection -eq $true) {
            $trusted = Get-TrustedSelectionContext -Row $row
            if ($null -eq $trusted) {
                throw "trusted selection source unresolved for $caseId; run the same-paper case-09 first"
            }
            $artifactResponse = Invoke-ApiJson -Method Get -Uri "$base/papers/$([int]$row.paperId)/layout-artifact"
            $documentHash = [string]$artifactResponse.data.documentHash
            if ([string]::IsNullOrWhiteSpace($documentHash)) {
                throw "paper document hash unavailable for $caseId"
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$trusted.documentHash) -and
                    [string]$trusted.documentHash -ne $documentHash) {
                throw "trusted selection belongs to a stale paper version for $caseId"
            }
            $targetText = [string]$trusted.targetText
            if ([string]::IsNullOrWhiteSpace($targetText)) {
                $targetText = [string]$row.context.selection.text
            }
            $contentType = if ([string]$trusted.precision -match 'FORMULA') { 'FORMULA' } else { 'TEXT' }
            $maxSelectionCharacters = if ($contentType -eq 'FORMULA') { 2000 } else { 4000 }
            if ($targetText.Length -gt $maxSelectionCharacters) {
                $targetText = [string]$row.context.selection.text
            }
            if ($targetText.Length -gt $maxSelectionCharacters) {
                $targetText = $targetText.Substring(0, $maxSelectionCharacters)
            }
            $selectedContent = [ordered]@{
                selectionId = "eval-$caseId-selection"
                paperId = [int]$row.paperId
                documentHash = $documentHash
                pageNumber = [int]$trusted.pageNumber
                contentType = $contentType
                exactText = $targetText
                sourceObjectIds = @([string]$trusted.sourceObjectId)
            }
            $uiContext = [ordered]@{
                pageNumber = [int]$trusted.pageNumber
                zoom = $null
                activeTool = $null
            }
            Write-Output ("[{0}/{1}] {2} injected trusted selection source={3} page={4}" -f $index, $total, $caseId, $trusted.sourceObjectId, $trusted.pageNumber)
        }
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
            selectedContent = $selectedContent
            attachmentIds = @()
            formulaAttachmentIds = @()
            uiContext = $uiContext
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
        Write-ResultRecord -CaseId $caseId -Record $record
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
        if ($status -eq 'WAITING_CLIENT' -and -not $waitForClientAction) { break }
        if ($status -eq 'WAITING_CLIENT' -and -not $waitingClientReported) {
            Write-Output ("[{0}/{1}] {2} waiting for the open paper page to submit the action receipt" -f $index, $total, $caseId)
            $waitingClientReported = $true
        }
        Start-Sleep -Seconds 2
    }
    if ($null -eq $snapshot) { throw "run status unavailable: $caseId" }
    if (($terminalStatuses -notcontains [string]$snapshot.data.status) -and
            -not ([string]$snapshot.data.status -eq 'WAITING_CLIENT' -and -not $waitForClientAction)) {
        $timedOut = $true
        try { Invoke-ApiJson -Method Post -Uri "$base/agent/turns/runs/$runId/cancel" -Body @{} | Out-Null } catch { }
        $snapshot = Get-RunSnapshot -RunId $runId
    }
    $eventResponse = Get-RunEvents -RunId $runId
    $record = New-ResultRecord -Row $row -SessionId $sessionId -RunId $runId -Snapshot $snapshot -Events $eventResponse -Polls $polls
    if ($timedOut) { $record.status = 'TIMEOUT' }
    Write-ResultRecord -CaseId $caseId -Record $record
    $requiredText = (@($record.requiredSkills) -join ',')
    $allowedText = (@($record.allowedSkills) -join ',')
    $predictedText = (@($record.predictedSkills) -join ',')
    Write-Output ("[{0}/{1}] {2} paper={3} status={4} required=[{5}] allowed=[{6}] predicted=[{7}]" -f $index, $total, $caseId, $record.paperId, $record.status, $requiredText, $allowedText, $predictedText)
}

Write-Output "pilot result file: $ResultPath"
