# Roadmap do Nautrix

Legenda: **concluído** significa implementado e verificado pelo critério indicado; **parcial** não
significa funcionalidade pronta para produção.

## Estado preservado

- [x] Requisitos e auditoria da arquitetura WebView e das branches Chromium.
- [x] Protótipo WebView compilando em debug e release no GitHub Actions.
- [x] Assinatura e upload dos APKs pelo CI.
- [x] Versão `webview-v0.4.0` preservada antes das correções de segurança.
- [ ] Testes funcionais do protótipo em aparelho.

## Fase 1 — manutenção segura do protótipo

- [x] Migrar para `targetSdk 36`.
- [x] Remover a solicitação obrigatória de bateria irrestrita no primeiro início.
- [x] Solicitar notificações somente quando o usuário iniciar uma transferência longa.
- [x] Exigir gesto e confirmação para abrir aplicativos externos.
- [x] Remover DNS UDP/53 e o proxy CONNECT local; usar o DNS seguro configurado no Android.
- [x] Limitar cookies de mídia/torrent à URL de destino e reduzir o referer à origem.
- [x] Evitar iniciar o serviço torrent quando não existem tarefas persistidas.
- [x] Validar as correções no GitHub Actions ([execução 24](https://github.com/mycroft440/nautrix/actions/runs/33258020942)).
- [ ] Instalar o APK e executar smoke test em aparelho.

## Fase 2 — primeiro APK Chromium

- [ ] Criar a branch `integration/chromium` a partir do material experimental.
- [ ] Fazer checkout reproduzível do SHA Chromium fixado.
- [ ] Corrigir sincronização do NDK, CIPD e `depot_tools` no CI.
- [ ] Compilar primeiro o Chromium sem overlays Nautrix.
- [ ] Publicar um `chrome_public_apk` instalável.
- [ ] Instalar e testar navegação, abas, permissões e downloads.

## Fases seguintes

- [ ] Aplicar marca, interface e políticas do Nautrix ao Chromium.
- [ ] Habilitar e validar extensões Manifest V3 no Android.
- [ ] Integrar adblock e privacidade na camada de rede do Chromium.
- [ ] Finalizar downloads, torrents, fast-resume e cache inteligente.
- [ ] Implementar instalação PWA real e fallback como atalho.
- [ ] Implementar player com MediaSessionService, background e Picture-in-Picture.
- [ ] Encerrar abas novas após 1 hora e abas inativas após 5 dias.
- [ ] Criar testes instrumentados, matriz de aparelhos e release de produção.
