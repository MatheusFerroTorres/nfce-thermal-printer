$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

. (Join-Path $PSScriptRoot "nfce-task-common.ps1")

try {
    Import-Module ScheduledTasks -ErrorAction Stop
    $taskName = Get-NfceTaskName
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -eq $task) {
        throw "Tarefa nao instalada. Execute instalar-tarefa.ps1 primeiro."
    }

    if ($task.State.ToString() -eq "Running") {
        Write-Host "O monitor ja esta em execucao."
        exit 0
    }

    $watchers = @(Get-NfceWatcherProcess)
    if ($watchers.Count -gt 0) {
        $processIds = ($watchers | ForEach-Object { $_.ProcessId }) -join ", "
        throw "Existe um monitor Java fora da tarefa (PID: $processIds). Encerre-o antes de iniciar a tarefa."
    }

    Start-ScheduledTask -TaskName $taskName
    Start-Sleep -Seconds 3
    $task = Get-ScheduledTask -TaskName $taskName
    if ($task.State.ToString() -ne "Running") {
        throw "A tarefa foi acionada, mas o monitor nao permaneceu em execucao. Execute status-tarefa.ps1."
    }

    Write-Host "Monitor iniciado pela tarefa: $taskName"
    Write-NfceConfigurationSummary
}
catch {
    Write-Error "Nao foi possivel iniciar o monitor: $($_.Exception.Message)"
    exit 1
}
