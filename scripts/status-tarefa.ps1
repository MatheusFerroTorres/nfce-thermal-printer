$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

. (Join-Path $PSScriptRoot "nfce-task-common.ps1")

try {
    Import-Module ScheduledTasks -ErrorAction Stop
    $taskName = Get-NfceTaskName
    $installDirectory = Get-NfceInstallDirectory
    $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue

    Write-Host "Tarefa:       $taskName"
    Write-Host "Diretorio:    $installDirectory"

    if ($null -eq $task) {
        Write-Host "Estado:       NAO INSTALADA"
    }
    else {
        $taskInfo = Get-ScheduledTaskInfo -TaskName $taskName
        $resultBytes = [BitConverter]::GetBytes([int32]$taskInfo.LastTaskResult)
        $resultUnsigned = [BitConverter]::ToUInt32($resultBytes, 0)
        $resultHex = "0x{0:X8}" -f $resultUnsigned

        Write-Host "Estado:       $($task.State)"
        Write-Host "Ultimo inicio: $($taskInfo.LastRunTime)"
        Write-Host "Resultado:    $($taskInfo.LastTaskResult) ($resultHex)"
        Write-Host "Proximo login: inicia automaticamente para o usuario instalado"
    }

    Write-NfceConfigurationSummary

    $watchers = @(Get-NfceWatcherProcess)
    if ($watchers.Count -eq 0) {
        Write-Host "Processo:     nenhum java/javaw --watch localizado"
    }
    else {
        foreach ($watcher in $watchers) {
            Write-Host "Processo:     PID $($watcher.ProcessId), $($watcher.Name)"
        }
    }

    $logsDirectory = Join-Path $installDirectory "logs"
    $latestLog = Get-ChildItem -LiteralPath $logsDirectory -Filter "nfce-printer.log*" -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $latestLog) {
        Write-Host "Log:          nenhum arquivo localizado em $logsDirectory"
    }
    else {
        Write-Host "Log:          $($latestLog.FullName)"
        Write-Host ""
        Write-Host "Ultimas 25 linhas:"
        Get-Content -LiteralPath $latestLog.FullName -Tail 25
    }
}
catch {
    Write-Error "Nao foi possivel consultar o status: $($_.Exception.Message)"
    exit 1
}
