$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

. (Join-Path $PSScriptRoot "nfce-task-common.ps1")

try {
    Import-Module ScheduledTasks -ErrorAction Stop
    $taskName = Get-NfceTaskName
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -eq $task) {
        Write-Host "A tarefa nao esta instalada."
        exit 0
    }

    if ($task.State.ToString() -eq "Running") {
        Stop-ScheduledTask -TaskName $taskName
        if (-not (Wait-NfceTaskState -ExpectedState "Ready" -TimeoutSeconds 15)) {
            throw "A tarefa nao parou dentro do tempo esperado."
        }
    }

    Write-Host "Monitor da tarefa parado."
    Write-Host "Os arquivos, a configuracao e o diario de duplicidade foram preservados."
}
catch {
    Write-Error "Nao foi possivel parar o monitor: $($_.Exception.Message)"
    exit 1
}
