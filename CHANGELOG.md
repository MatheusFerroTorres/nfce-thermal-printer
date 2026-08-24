# Histórico de versões

Todas as mudanças relevantes deste projeto serão registradas neste arquivo.

## 0.5.0 — 2026-08-24

- permite reimpressões intencionais da mesma NFC-e quando o SIGALOJA gera novamente o PDF;
- mantém o bloqueio de reenvio quando o resultado anterior ficou incerto;
- retira o original da raiz antes de enviar o trabalho ao spooler;
- preserva arquivos em `Erro` quando a impressão precisa de conferência manual;
- informa a política de reimpressão no comando de status;
- mantém conversão vetorial, QR Code original e página térmica com altura dinâmica;
- mantém instalação por usuário e inicialização automática pelo Agendador de Tarefas.

## Política de versionamento

O projeto segue versões `MAJOR.MINOR.PATCH`. Alterações no leiaute ou no fluxo de
impressão somente são consideradas estáveis depois de preview, teste físico e
leitura do QR Code.
