Set-StrictMode -Version 2.0

$script:NfceTaskName = "AlkhemyLab NFC-e Printer"

function Get-NfceTaskName {
    return $script:NfceTaskName
}

function Get-NfceInstallDirectory {
    if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        throw "A variavel LOCALAPPDATA nao esta disponivel para este usuario."
    }

    return Join-Path $env:LOCALAPPDATA "AlkhemyLab\NfcePrinter"
}

function Get-NfceInstalledConfigPath {
    return Join-Path (Get-NfceInstallDirectory) "config\application.properties"
}

function Get-NfceConfigValue {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Name,

        [string]$ConfigPath = (Get-NfceInstalledConfigPath)
    )

    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        return $null
    }

    $escapedName = [regex]::Escape($Name)
    $line = Get-Content -LiteralPath $ConfigPath -Encoding UTF8 |
        Where-Object { $_ -match "^\s*$escapedName\s*=" } |
        Select-Object -Last 1

    if ($null -eq $line) {
        return $null
    }

    return ($line -split "=", 2)[1].Trim()
}

function Get-NfceWatcherProcess {
    try {
        return @(Get-CimInstance Win32_Process -ErrorAction Stop |
            Where-Object {
                $_.Name -in @("java.exe", "javaw.exe") -and
                $null -ne $_.CommandLine -and
                $_.CommandLine -match "(?i)nfce-printer\.jar.*--watch"
            })
    }
    catch {
        Write-Warning "Nao foi possivel consultar processos Java: $($_.Exception.Message)"
        return @()
    }
}

function Wait-NfceTaskState {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ExpectedState,

        [int]$TimeoutSeconds = 10
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $task = Get-ScheduledTask -TaskName (Get-NfceTaskName) -ErrorAction SilentlyContinue
        if ($null -eq $task -or $task.State.ToString() -eq $ExpectedState) {
            return $true
        }

        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)

    return $false
}

function Write-NfceConfigurationSummary {
    $configPath = Get-NfceInstalledConfigPath
    Write-Host "Configuracao: $configPath"

    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
        Write-Warning "Arquivo de configuracao instalado nao encontrado."
        return
    }

    $printerName = Get-NfceConfigValue -Name "printer.name" -ConfigPath $configPath
    $autoPrint = Get-NfceConfigValue -Name "auto.print" -ConfigPath $configPath
    $completedReprint = Get-NfceConfigValue -Name "watch.allow.completed.reprint" -ConfigPath $configPath
    $spoolDirectory = Get-NfceConfigValue -Name "spool.directory" -ConfigPath $configPath

    if ([string]::IsNullOrWhiteSpace($completedReprint)) {
        $completedReprint = "true (padrao incorporado ao JAR 0.5.0+)"
    }

    Write-Host "Spool:        $spoolDirectory"
    Write-Host "Impressora:   $printerName"
    Write-Host "auto.print:   $autoPrint"
    Write-Host "Reimpressao:  $completedReprint"
}
