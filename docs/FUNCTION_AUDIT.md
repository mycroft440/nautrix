# Auditoria funcional do Nautrix

## Aplicativo Android compilável

| Função | Estado | Implementação |
|---|---|---|
| Navegação web | Pronta | WebView do sistema, barra de endereço, pesquisa DuckDuckGo, voltar/avançar/recarregar/início |
| Abas | Pronta | Criar, alternar, fechar e restaurar até 12 abas da sessão |
| Downloads | Pronta | Central própria sobre o DownloadManager, cookies/referer/user-agent, progresso, abrir, cancelar e repetir |
| Torrent/magnet | Pronta | jlibtorrent/libtorrent v1, v2 e híbrido; magnet, `.torrent`, pausa, retomada, remoção preservando arquivos e restauração da lista |
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
| Download de mídia detectada | Pronta com limites | Botão superior detecta MP4/WebM/MOV/M4V e HLS/DASH expostos no DOM, metadados ou rede; fontes diretas vão para Downloads e streams adaptativos para player/cache |
| Cache de vídeo offline | Pronta | `SimpleCache` persistente; retém por no mínimo 5 dias e reabre, pela lista do menu, os trechos efetivamente carregados |
| DNS automático | Pronta | 20 resolvedores, três amostras por candidato, pontuação por média/jitter/falhas, proxy CONNECT local para WebView e DNS direto no player |
| Instalar página como app | Pronta | `requestPinShortcut`, ícone por site e atividade HTTPS independente sem chrome do navegador |
| APK debug | Pronta no CI | Assinado automaticamente pelo Android Gradle Plugin |
| APK release | Pronta no CI | Usa secrets de produção; sem secrets, recebe chave temporária de CI instalável |

## Limites atuais

- O aplicativo compilável usa o Android System WebView. Ele não inclui uma cópia completa do Chromium.
- Extensões Manifest V3 não são suportadas pelo System WebView.
- DoH personalizado depende do provedor WebView/sistema e ainda não tem seletor próprio no app.
- O cliente torrent salva em armazenamento externo administrado pelo app. A central permite abrir arquivos individuais; desinstalar o Nautrix pode apagar essa pasta. O Android 15 limita serviços `dataSync` longos e pode interromper a sessão, que é restaurada ao reabrir a central.
- O botão de mídia não é um mecanismo universal de extração: não burla DRM, paywall, login, assinatura, URLs `blob:` protegidas nem controles de acesso. Plataformas que separam faixas ou ocultam a URL podem oferecer somente reprodução/cache dos dados efetivamente recebidos.
- O player dedicado não contorna DRM nem fontes disponíveis exclusivamente como `blob:`. Nesses casos, o vídeo permanece no player WebView do site.
- O cache offline não inventa dados ausentes: se somente parte do vídeo foi recebida, somente essa parte é reproduzível sem rede. Limpar dados do Android ou usar “Limpar cache de vídeos” remove o conteúdo antes dos 5 dias por decisão do usuário.
- O DNS automático usa consultas DNS públicas clássicas e um túnel CONNECT local; o TLS HTTPS continua de ponta a ponta. Se a operadora bloquear DNS externo ou o WebView não aceitar override de proxy, o fallback é o DNS configurado no Android.
- A instalação de página cria um app/atalho hospedado pelo Nautrix; não converte o site em APK separado e depende de um launcher compatível com atalhos fixados.
- A assinatura temporária do CI serve para instalação e testes. Atualizações distribuídas publicamente precisam usar sempre a mesma chave configurada nos secrets.

## Material Chromium experimental preservado

Os arquivos em `overlays/chromium/`, `native/adblock_ffi/` e `scripts/integrate_chromium.py` foram mantidos para uma futura versão baseada em uma árvore completa do Chromium. Eles não fazem parte do APK standalone porque o repositório original não continha a árvore Chromium nem todos os arquivos referenciados por esses skeletons.
