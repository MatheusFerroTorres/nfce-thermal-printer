[CmdletBinding()]
param(
    [switch]$NoStart,
    [switch]$ReplaceConfig
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

. (Join-Path $PSScriptRoot "nfce-task-common.ps1")

function Resolve-NfceSourceJar {
    param([Parameter(Mandatory = $true)][string]$SourceRoot)

    $candidates = @(
        (Join-Path $SourceRoot "target\nfce-printer.jar"),
        (Join-Path $SourceRoot "nfce-printer.jar")
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw "nfce-printer.jar nao encontrado. Execute 'mvn clean package' na raiz do projeto."
}

function Copy-NfceFileIfDifferent {
    param(
        [Parameter(Mandatory = $true)][string]$Source,
        [Parameter(Mandatory = $true)][string]$Destination
    )

    $sourceFullPath = [System.IO.Path]::GetFullPath($Source)
    $destinationFullPath = [System.IO.Path]::GetFullPath($Destination)
    if (-not [string]::Equals($sourceFullPath, $destinationFullPath,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Copy-Item -LiteralPath $Source -Destination $Destination -Force
    }
}

try {
    Import-Module ScheduledTasks -ErrorAction Stop

    $sourceRoot = Split-Path -Parent $PSScriptRoot
    $sourceJar = Resolve-NfceSourceJar -SourceRoot $sourceRoot
    $sourceConfig = Join-Path $sourceRoot "config\application.properties"
    if (-not (Test-Path -LiteralPath $sourceConfig -PathType Leaf)) {
        throw "Configuracao de origem nao encontrada: $sourceConfig"
    }

    $javaCommand = Get-Command java.exe -CommandType Application -ErrorAction Stop
    $javaExe = $javaCommand.Source
    $javawExe = Join-Path (Split-Path -Parent $javaExe) "javaw.exe"
    if (-not (Test-Path -LiteralPath $javawExe -PathType Leaf)) {
        throw "javaw.exe nao encontrado ao lado de: $javaExe"
    }

    $taskName = Get-NfceTaskName
    $existingTask = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($null -ne $existingTask -and $existingTask.State.ToString() -eq "Running") {
        Write-Host "Parando a tarefa existente antes da atualizacao..."
        Stop-ScheduledTask -TaskName $taskName
        if (-not (Wait-NfceTaskState -ExpectedState "Ready" -TimeoutSeconds 15)) {
            throw "A tarefa existente nao parou dentro do tempo esperado."
        }
    }

    $watchers = @(Get-NfceWatcherProcess)
    if ($watchers.Count -gt 0) {
        $processIds = ($watchers | ForEach-Object { $_.ProcessId }) -join ", "
        throw "Existe um monitor manual ativo (PID: $processIds). Pressione Ctrl+C na janela dele e execute o instalador novamente."
    }

    $installDirectory = Get-NfceInstallDirectory
    $installedConfigDirectory = Join-Path $installDirectory "config"
    $installedLogsDirectory = Join-Path $installDirectory "logs"
    $installedScriptsDirectory = Join-Path $installDirectory "scripts"

    New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $installedConfigDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $installedLogsDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path $installedScriptsDirectory -Force | Out-Null

    $installedJar = Join-Path $installDirectory "nfce-printer.jar"
    Copy-NfceFileIfDifferent -Source $sourceJar -Destination $installedJar

    $installedConfig = Get-NfceInstalledConfigPath
    if (-not (Test-Path -LiteralPath $installedConfig -PathType Leaf)) {
        Copy-NfceFileIfDifferent -Source $sourceConfig -Destination $installedConfig
        Write-Host "Configuracao instalada a partir do pacote."
    }
    elseif ($ReplaceConfig) {
        $timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $backup = "$installedConfig.$timestamp.bak"
        Copy-Item -LiteralPath $installedConfig -Destination $backup -Force
        Copy-NfceFileIfDifferent -Source $sourceConfig -Destination $installedConfig
        Write-Host "Configuracao anterior preservada em: $backup"
    }
    else {
        Write-Host "Configuracao instalada existente preservada."
    }

    Get-ChildItem -LiteralPath $PSScriptRoot -Filter "*.ps1" -File |
        ForEach-Object {
            Copy-NfceFileIfDifferent -Source $_.FullName -Destination (Join-Path $installedScriptsDirectory $_.Name)
        }

    $sourceReadme = Join-Path $sourceRoot "README.md"
    if (Test-Path -LiteralPath $sourceReadme -PathType Leaf) {
        Copy-NfceFileIfDifferent -Source $sourceReadme -Destination (Join-Path $installDirectory "README.md")
    }

    Push-Location $installDirectory
    try {
        & $javaExe -jar $installedJar --help | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "O JAR instalado falhou na verificacao com --help. Codigo: $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }

    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $actionArguments = '-jar "{0}" --watch' -f $installedJar
    $action = New-ScheduledTaskAction `
        -Execute $javawExe `
        -Argument $actionArguments `
        -WorkingDirectory $installDirectory
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $currentUser
    $principal = New-ScheduledTaskPrincipal `
        -UserId $currentUser `
        -LogonType Interactive `
        -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet `
        -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries `
        -StartWhenAvailable `
        -MultipleInstances IgnoreNew `
        -RestartCount 3 `
        -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero)
    $task = New-ScheduledTask `
        -Action $action `
        -Trigger $trigger `
        -Principal $principal `
        -Settings $settings `
        -Description "Monitora PDFs de NFC-e do Protheus e os prepara para a impressora termica."

    Register-ScheduledTask -TaskName $taskName -InputObject $task -Force | Out-Null
    Write-Host "Tarefa registrada para o login de: $currentUser"

    if (-not $NoStart) {
        Start-ScheduledTask -TaskName $taskName
        Start-Sleep -Seconds 3
        $registeredTask = Get-ScheduledTask -TaskName $taskName
        if ($registeredTask.State.ToString() -ne "Running") {
            $latestLog = Get-ChildItem -LiteralPath $installedLogsDirectory -Filter "nfce-printer.log*" -File -ErrorAction SilentlyContinue |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
            if ($null -ne $latestLog) {
                Write-Host "Ultimas linhas do log:"
                Get-Content -LiteralPath $latestLog.FullName -Tail 20
            }
            throw "A tarefa foi registrada, mas o monitor nao permaneceu em execucao. Consulte o status e o log."
        }
    }

    Write-Host ""
    Write-Host "Instalacao concluida."
    Write-Host "Tarefa:       $taskName"
    Write-Host "Diretorio:    $installDirectory"
    Write-Host "Java:         $javawExe"
    Write-NfceConfigurationSummary

    if ($NoStart) {
        Write-Host "Estado:       instalada, sem iniciar (-NoStart)"
    }
    else {
        Write-Host "Estado:       monitor em execucao"
    }

    Write-Host ""
    Write-Host "Status: powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$installedScriptsDirectory\status-tarefa.ps1`""
}
catch {
    Write-Error "Falha na instalacao da tarefa: $($_.Exception.Message)"
    exit 1
}
