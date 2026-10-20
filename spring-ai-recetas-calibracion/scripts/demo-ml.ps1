param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$DatasetPath = "$PSScriptRoot/../src/test/resources/ml/recipes-balanced.json",
    [string]$OutputPath = "$PSScriptRoot/../target/ml-demo/report.json"
)

$ErrorActionPreference = 'Stop'

function Send-Json([string]$Route, $Payload, [string]$Key) {
    $headers = @{}
    if ($Key) {
        $headers['Idempotency-Key'] = $Key
    }
    $json = ConvertTo-Json -InputObject $Payload -Depth 30
    Invoke-RestMethod -Uri "$BaseUrl/api/ml/$Route" -Method Post -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($json))
}

function Wait-JobResult([string]$Route) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    do {
        $result = Invoke-RestMethod -Uri "$BaseUrl/api/ml/$Route"
        if ($result.status -eq 'SUCCEEDED') {
            return $result
        }
        if ($result.status -in @('FAILED', 'INCOMPLETE')) {
            throw "El trabajo termino en $($result.status): $($result.error)"
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Tiempo agotado esperando $Route"
}

$dataset = Get-Content -Raw -Encoding UTF8 -LiteralPath $DatasetPath | ConvertFrom-Json
$imported = Send-Json 'datasets' $dataset
$split = Send-Json "datasets/$($imported.datasetVersionId)/splits" @{
    trainPercent = 70; validationPercent = 15; testPercent = 15
    seed = 42; strategy = 'STRATIFIED_GROUP'; maxDeviationPercentagePoints = 5
}

$candidates = @()
foreach ($alpha in @(0.1, 1.0, 10.0)) {
    $experiment = Send-Json 'experiments' @{
        splitId = $split.splitId; mode = 'LOCAL_TRAINABLE'; alpha = $alpha
    } "train-$($split.splitId)-$alpha"
    $trained = Wait-JobResult "experiments/$($experiment.experimentId)"
    $validation = Send-Json "experiments/$($trained.experimentId)/validation" @{} 'validation'
    $validated = Wait-JobResult "evaluations/$($validation.evaluationId)"
    $candidates += [PSCustomObject]@{
        experimentId = $trained.experimentId
        validationId = $validated.evaluationId
        macroF1 = $validated.metrics.macroF1
        alphaOrder = $alpha.ToString([Globalization.CultureInfo]::InvariantCulture)
    }
}

$selected = $candidates | Sort-Object @{Expression = 'macroF1'; Descending = $true}, alphaOrder |
    Select-Object -First 1
$null = Send-Json "experiments/$($selected.experimentId)/freeze" @{validationId = $selected.validationId}
$test = Send-Json "experiments/$($selected.experimentId)/test" @{} 'final-test'
$report = Wait-JobResult "evaluations/$($test.evaluationId)"
$parent = Split-Path -Parent ([IO.Path]::GetFullPath($OutputPath))
$null = New-Item -ItemType Directory -Path $parent -Force
$report | ConvertTo-Json -Depth 50 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
[PSCustomObject]@{
    datasetVersionId = $imported.datasetVersionId
    splitId = $split.splitId
    counts = $split.counts
    experimentId = $selected.experimentId
    evaluationId = $report.evaluationId
    accuracy = $report.metrics.accuracy
    macroF1 = $report.metrics.macroF1
    matrix = $report.metrics.matrix
    report = [IO.Path]::GetFullPath($OutputPath)
} | ConvertTo-Json -Depth 10
