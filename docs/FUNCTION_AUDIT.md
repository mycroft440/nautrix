# Auditoria funcional do Nautrix

## Aplicativo Android compilável

| Função | Estado | Implementação |
|---|---|---|
| Navegação web | Pronta | WebView do sistema, barra de endereço, pesquisa DuckDuckGo, voltar/avançar/recarregar/início |
| Abas | Pronta | Criar, alternar, fechar e restaurar até 12 abas da sessão |
| Downloads | Pronta | DownloadManager, cookies e user-agent da sessão, operação em segundo plano |
| Upload de arquivos | Pronta | Seletor de documentos do Android |
| Favoritos | Pronta | Salvos localmente e acessíveis pelo menu |
| Compartilhamento | Pronta | Android Sharesheet |
| Modo desktop | Pronta | User-agent desktop por aba |
| Câmera/microfone em sites | Pronta | Confirmação do Android e confirmação adicional por origem |
| Navegação segura | Pronta | Safe Browsing do WebView, TLS inválido bloqueado e cleartext desativado |
| Bloqueio de anúncios | Pronta | `adblock-rust` 0.13.3, EasyList, EasyPrivacy, filtros cosméticos, cache e permissão por site |
| Player de vídeo | Pronta | Media3/ExoPlayer, MP4/WebM/HLS/DASH, controles nativos, retomada e buffer ampliado |
| Diagnóstico do vídeo | Pronta | Detecta conexão, buffering e falhas; avisa “Servidor do site lento!” após 8 s iniciais ou 5 s de rebuffer |
| Sessão no player | Pronta | Repassa HTTPS, cookies, referer e user-agent usados pelo site |
| APK debug | Pronta no CI | Assinado automaticamente pelo Android Gradle Plugin |
| APK release | Pronta no CI | Usa secrets de produção; sem secrets, recebe chave temporária de CI instalável |

## Limites atuais

- O aplicativo compilável usa o Android System WebView. Ele não inclui uma cópia completa do Chromium.
- Extensões Manifest V3 não são suportadas pelo System WebView.
- DoH personalizado depende do provedor WebView/sistema e ainda não tem seletor próprio no app.
- Torrent/magnet é entregue a um aplicativo externo instalado. O serviço libtorrent antigo permanece apenas como overlay experimental.
- O player dedicado não contorna DRM nem fontes disponíveis exclusivamente como `blob:`. Nesses casos, o vídeo permanece no player WebView do site.
- A assinatura temporária do CI serve para instalação e testes. Atualizações distribuídas publicamente precisam usar sempre a mesma chave configurada nos secrets.

## Material Chromium experimental preservado

Os arquivos em `overlays/chromium/`, `native/adblock_ffi/` e `scripts/integrate_chromium.py` foram mantidos para uma futura versão baseada em uma árvore completa do Chromium. Eles não fazem parte do APK standalone porque o repositório original não continha a árvore Chromium nem todos os arquivos referenciados por esses skeletons.
