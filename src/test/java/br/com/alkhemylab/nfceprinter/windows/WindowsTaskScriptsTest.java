package br.com.alkhemylab.nfceprinter.windows;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsTaskScriptsTest {
    private static final Path SCRIPTS = Path.of("scripts");

    @Test
    void providesAllMaintenanceCommands() {
        List<String> expected = List.of(
                "nfce-task-common.ps1",
                "instalar-tarefa.ps1",
                "iniciar-tarefa.ps1",
                "parar-tarefa.ps1",
                "status-tarefa.ps1",
                "remover-tarefa.ps1");

        for (String filename : expected) {
            Path script = SCRIPTS.resolve(filename);
            assertTrue(Files.isRegularFile(script), () -> "Script ausente: " + script);
        }
    }

    @Test
    void installerUsesInteractiveLogonAndLongRunningJavawTask() throws IOException {
        String installer = read("instalar-tarefa.ps1");

        assertTrue(installer.contains("javaw.exe"));
        assertTrue(installer.contains("New-ScheduledTaskTrigger -AtLogOn -User"));
        assertTrue(installer.contains("-LogonType Interactive"));
        assertTrue(installer.contains("-RunLevel Limited"));
        assertTrue(installer.contains("-MultipleInstances IgnoreNew"));
        assertTrue(installer.contains("-RestartCount 3"));
        assertTrue(installer.contains("-ExecutionTimeLimit ([TimeSpan]::Zero)"));
        assertTrue(installer.contains("-WorkingDirectory $installDirectory"));
        assertFalse(installer.contains("-RunLevel Highest"));
    }

    @Test
    void installerGuardsManualWatcherAndPreservesExistingConfiguration() throws IOException {
        String common = read("nfce-task-common.ps1");
        String installer = read("instalar-tarefa.ps1");

        assertTrue(common.contains("$env:LOCALAPPDATA"));
        assertTrue(common.contains("nfce-printer\\.jar.*--watch"));
        assertTrue(installer.contains("Get-NfceWatcherProcess"));
        assertTrue(installer.contains("elseif ($ReplaceConfig)"));
        assertTrue(installer.contains("Configuracao instalada existente preservada"));
        assertTrue(installer.contains(".bak"));
    }

    @Test
    void removalNeverDeletesInstallationOrSpoolFiles() throws IOException {
        String removal = read("remover-tarefa.ps1");

        assertTrue(removal.contains("Unregister-ScheduledTask"));
        assertFalse(removal.contains("Remove-Item"));
        assertTrue(removal.contains("nao foram apagados"));
    }

    @Test
    void scriptsAvoidPowerShellSevenOnlyConstructs() throws IOException {
        try (var paths = Files.list(SCRIPTS)) {
            for (Path script : paths.filter(path -> path.toString().endsWith(".ps1")).toList()) {
                String contents = Files.readString(script, StandardCharsets.UTF_8);
                assertFalse(contents.contains("ForEach-Object -Parallel"), script::toString);
                assertFalse(contents.contains("$IsWindows"), script::toString);
                assertFalse(contents.contains("??="), script::toString);
            }
        }
    }

    private static String read(String filename) throws IOException {
        return Files.readString(SCRIPTS.resolve(filename), StandardCharsets.UTF_8);
    }
}
