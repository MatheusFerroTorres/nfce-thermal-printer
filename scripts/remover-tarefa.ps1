$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

. (Join-Path $PSScriptRoot "nfce-task-common.ps1")

try {
    Import-Module ScheduledTasks -ErrorAction Stop
    $taskName = Get-NfceTaskName
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -eq $task) {
        Write-Host "A tarefa nao esta instalada."
    }
    else {
        if ($task.State.ToString() -eq "Running") {
            Stop-ScheduledTask -TaskName $taskName
            if (-not (Wait-NfceTaskState -ExpectedState "Ready" -TimeoutSeconds 15)) {
                throw "A tarefa nao parou dentro do tempo esperado."
            }
        }

        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
        Write-Host "Tarefa removida: $taskName"
    }

    Write-Host "Diretorio preservado: $(Get-NfceInstallDirectory)"
    Write-Host "C:\Spool, configuracao, logs e diario de duplicidade nao foram apagados."
}
catch {
    Write-Error "Nao foi possivel remover a tarefa: $($_.Exception.Message)"
    exit 1
}
