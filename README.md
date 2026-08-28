# Nautrix Browser

Nautrix agora contém um navegador Android standalone compilável, além dos overlays experimentais para uma futura distribuição completa baseada em Chromium.

## O que funciona no APK

- navegação por HTTPS e pesquisa DuckDuckGo;
- múltiplas abas com restauração da sessão;
- central de downloads com progresso, tamanho, abrir, cancelar e tentar novamente;
- cliente torrent interno baseado em libtorrent, com magnet, arquivos `.torrent`, pausa e retomada;
- upload de arquivos;
- favoritos, compartilhamento e modo desktop;
- câmera e microfone somente após autorização do Android e do usuário;
- Safe Browsing, bloqueio de TLS inválido, sem tráfego HTTP em texto claro;
- bloqueio nativo com o motor [`brave/adblock-rust`](https://github.com/brave/adblock-rust), EasyList, EasyPrivacy e filtros cosméticos;
- proteção ativável/desativável por site e contador de bloqueios por aba;
- player dedicado com Media3/ExoPlayer para MP4, WebM, HLS e DASH;
- detecção de vídeos na página, preservando cookies, referência e user-agent da sessão;
- botão **⇩** no canto superior para baixar fontes MP4/WebM/MOV expostas pela página ou abrir HLS/DASH no player/cache;
- feedback de conexão, buffer, falta de internet, erro e aviso **“Servidor do site lento!”**;
- cache persistente durante a reprodução, com retenção mínima de 5 dias e lista **Vídeos em cache** para rever offline os trechos já carregados;
- DNS automático: mede 20 resolvedores com três amostras, pontua latência, variação e falhas e aplica o vencedor ao WebView e ao player;
- instalação de páginas HTTPS na tela inicial, abertas como app sem as barras do navegador;
- tema escuro.

Para usar o player, abra uma página com vídeo e selecione **Menu → Abrir vídeo no player**.
O Nautrix tenta usar a fonte do elemento de vídeo ou um stream de mídia detectado durante o
carregamento da página. Conteúdo protegido por DRM ou exposto somente como URL `blob:` pode
continuar limitado ao player do próprio site.

O botão **⇩** examina elementos de vídeo, metadados e solicitações de mídia que a página já
expôs ao navegador. Fontes diretas podem ser salvas em `Downloads/Nautrix`; streams HLS/DASH são
abertos no player e entram no cache conforme são recebidos. O Nautrix não burla DRM, paywalls,
login, URLs `blob:` protegidas nem controles de acesso, por isso não promete compatibilidade com
todo vídeo de toda rede social.

Em **Menu → Downloads**, a mesma tela acompanha downloads diretos e torrents. Magnets podem ser
colados e arquivos `.torrent` escolhidos pelo seletor do Android. Torrents continuam em primeiro
plano com notificação, podem ser pausados ou retomados e salvam dados em uma pasta administrada
pelo app, acessível pela seção **Arquivos de torrents**. No Android 15 ou superior, o sistema pode
interromper serviços de dados muito longos; reabrir a central restaura a sessão e verifica as partes
já gravadas.

O cache é preenchido conforme o vídeo toca ou entra no buffer. Sem internet, apenas os trechos já
armazenados podem ser reproduzidos; o Nautrix não afirma ter baixado as partes que nunca foram
recebidas. O app não remove conteúdo com menos de 5 dias, salvo se o usuário limpar os dados ou o
cache pelo menu.

Em **Menu → DNS automático**, é possível ver o resolvedor escolhido e executar um novo teste. O
proxy é local ao processo do Nautrix e mantém o TLS entre o WebView e o site; se DNS externo estiver
bloqueado pela rede, o navegador volta automaticamente ao resolvedor do Android.

Em **Menu → Instalar página como app**, o Android pede confirmação para adicionar um ícone à tela
inicial. O atalho abre a página em uma atividade própria, sem a barra de endereço e a navegação do
Nautrix.

Veja a [auditoria funcional](docs/FUNCTION_AUDIT.md) para os limites e o estado exato de cada item.

## Build pelo GitHub Actions

O workflow **Android APKs** executa testes, compila o motor Rust para ARM64, ARMv7 e x86_64,
integra as bibliotecas libtorrent correspondentes, gera os APKs e verifica suas assinaturas.

Artefatos gerados:

- `Nautrix-debug/Nautrix-debug.apk`
- `Nautrix-release/Nautrix-release.apk`

O workflow roda em todo push na `main` e também pode ser iniciado por **Actions → Android APKs → Run workflow**.

### Assinatura release

Para uma assinatura permanente de produção, configure estes secrets no repositório:

- `NAUTRIX_RELEASE_KEYSTORE_BASE64`
- `NAUTRIX_RELEASE_STORE_PASSWORD`
- `NAUTRIX_RELEASE_KEY_ALIAS`
- `NAUTRIX_RELEASE_KEY_PASSWORD`

Sem esses secrets, o workflow cria uma chave temporária de CI. O APK será instalável, mas não deve ser publicado nem usado para atualizar uma versão anterior.

## Build local

Requisitos: Java 17, Android SDK 36, NDK 27.2, Rust estável, `cargo-ndk` 4.1.2 e Gradle 8.11.1.

```bash
cd native/adblock_android
cargo ndk \
  --target arm64-v8a \
  --target armeabi-v7a \
  --target x86_64 \
  --output-dir ../../app/src/main/jniLibs \
  build --release
cd ../..

gradle testDebugUnitTest assembleDebug
```

## Estrutura

- `app/` — navegador Android standalone;
- `native/adblock_android/` — JNI do motor Brave adblock-rust usado pelo APK;
- `overlays/chromium/` — pesquisa/integrações experimentais preservadas;
- `native/adblock_ffi/` — FFI anterior para uma árvore Chromium completa;
- `scripts/verify_project.py` — validação rápida do contrato do projeto.
