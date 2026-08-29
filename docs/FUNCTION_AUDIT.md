# Auditoria funcional do Nautrix

## Aplicativo Android compilável

| Função | Estado | Implementação |
|---|---|---|
| Navegação web | Pronta | WebView do sistema, barra de endereço, pesquisa DuckDuckGo, voltar/avançar/recarregar/início |
| Abas | Pronta | Criar, alternar, fechar e restaurar até 12 abas da sessão |
| Downloads | Parcial | Central sobre o DownloadManager, progresso, abrir, cancelar e repetir; downloads autenticados e retomada controlada ainda precisam de implementação própria |
| Torrent/magnet | Parcial | jlibtorrent/libtorrent v1, v2 e híbrido; magnet, `.torrent`, pausa, retomada e lista persistida, ainda sem fast-resume validado em aparelho |
| Upload de arquivos | Pronta | Seletor de documentos do Android |
| Favoritos | Pronta | Salvos localmente e acessíveis pelo menu |
| Compartilhamento | Pronta | Android Sharesheet |
| Modo desktop | Pronta | User-agent desktop por aba |
| Câmera/microfone em sites | Pronta | Confirmação do Android e confirmação adicional por origem |
| Navegação segura | Pronta | Safe Browsing do WebView, TLS inválido bloqueado e cleartext desativado |
| Bloqueio de anúncios | Parcial | `adblock-rust` 0.13.3, EasyList, EasyPrivacy e filtros cosméticos; faltam testes em sites reais e proteções equivalentes ao Brave |
| Player de vídeo | Parcial | Media3/ExoPlayer e caminhos MP4/WebM/HLS/DASH; falta validação em aparelho, PiP, background e DRM |
| Diagnóstico do vídeo | Parcial | Política de conexão, buffering e falhas coberta por teste unitário; falta teste com servidores reais |
| Sessão no player | Parcial | Cookies são obtidos por URL de destino e o referer é reduzido à origem; falta matriz de autenticação e redirects |
| Download de mídia detectada | Pronta com limites | Botão superior detecta MP4/WebM/MOV/M4V e HLS/DASH expostos no DOM, metadados ou rede; fontes diretas vão para Downloads e streams adaptativos para player/cache |
| Cache de vídeo offline | Parcial | `SimpleCache` persistente; retém trechos recebidos por no mínimo 5 dias, sem garantir o arquivo completo |
| DNS seguro | Pronta no protótipo | Usa o resolvedor do Android e respeita DNS privado; o antigo UDP/53 e o proxy local foram removidos |
| Instalar página como app | Parcial | `requestPinShortcut` e atividade sem chrome; é um atalho hospedado, não uma PWA/WebAPK |
| APK debug | Pronta no CI | Assinado automaticamente pelo Android Gradle Plugin |
| APK release | Pronta no CI | Usa secrets de produção; sem secrets, recebe chave temporária de CI instalável |

## Limites atuais

- O aplicativo compilável usa o Android System WebView. Ele não inclui uma cópia completa do Chromium.
- Extensões Manifest V3 não são suportadas pelo System WebView.
- DoH personalizado depende do provedor WebView/sistema e ainda não tem seletor próprio no app. A seleção será feita no Chromium.
- O cliente torrent salva em armazenamento externo administrado pelo app. A central permite abrir arquivos individuais; desinstalar o Nautrix pode apagar essa pasta. O Android 15 limita serviços `dataSync` longos e pode interromper a sessão, que é restaurada ao reabrir a central.
- O botão de mídia não é um mecanismo universal de extração: não burla DRM, paywall, login, assinatura, URLs `blob:` protegidas nem controles de acesso. Plataformas que separam faixas ou ocultam a URL podem oferecer somente reprodução/cache dos dados efetivamente recebidos.
- O player dedicado não contorna DRM nem fontes disponíveis exclusivamente como `blob:`. Nesses casos, o vídeo permanece no player WebView do site.
- O cache offline não inventa dados ausentes: se somente parte do vídeo foi recebida, somente essa parte é reproduzível sem rede. Limpar dados do Android ou usar “Limpar cache de vídeos” remove o conteúdo antes dos 5 dias por decisão do usuário.
- O protótipo usa somente o resolvedor do Android. As consultas UDP/53 externas e o túnel CONNECT local foram removidos por privacidade e resistência a spoofing.
- A instalação de página cria um app/atalho hospedado pelo Nautrix; não converte o site em APK separado e depende de um launcher compatível com atalhos fixados.
- A assinatura temporária do CI serve para instalação e testes. Atualizações distribuídas publicamente precisam usar sempre a mesma chave configurada nos secrets.

## Material Chromium experimental preservado

Os arquivos em `overlays/chromium/`, `native/adblock_ffi/` e `scripts/integrate_chromium.py` foram mantidos para uma futura versão baseada em uma árvore completa do Chromium. Eles não fazem parte do APK standalone porque o repositório original não continha a árvore Chromium nem todos os arquivos referenciados por esses skeletons.
