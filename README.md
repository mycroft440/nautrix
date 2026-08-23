# Nautrix Browser

Nautrix agora contém um navegador Android standalone compilável, além dos overlays experimentais para uma futura distribuição completa baseada em Chromium.

## O que funciona no APK

- navegação por HTTPS e pesquisa DuckDuckGo;
- múltiplas abas com restauração da sessão;
- downloads em segundo plano pelo DownloadManager;
- upload de arquivos;
- favoritos, compartilhamento e modo desktop;
- câmera e microfone somente após autorização do Android e do usuário;
- Safe Browsing, bloqueio de TLS inválido, sem tráfego HTTP em texto claro;
- bloqueio nativo com o motor [`brave/adblock-rust`](https://github.com/brave/adblock-rust), EasyList, EasyPrivacy e filtros cosméticos;
- proteção ativável/desativável por site e contador de bloqueios por aba;
- tema escuro.

Veja a [auditoria funcional](docs/FUNCTION_AUDIT.md) para os limites e o estado exato de cada item.

## Build pelo GitHub Actions

O workflow **Android APKs** executa testes, compila o motor Rust para ARM64, ARMv7 e x86_64, gera os APKs e verifica suas assinaturas.

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

Requisitos: Java 17, Android SDK 35, NDK 27.2, Rust estável, `cargo-ndk` 4.1.2 e Gradle 8.9.

```bash
cargo ndk \
  --target arm64-v8a \
  --target armeabi-v7a \
  --target x86_64 \
  --output-dir app/src/main/jniLibs \
  build --release --manifest-path native/adblock_android/Cargo.toml

gradle testDebugUnitTest assembleDebug
```

## Estrutura

- `app/` — navegador Android standalone;
- `native/adblock_android/` — JNI do motor Brave adblock-rust usado pelo APK;
- `overlays/chromium/` — pesquisa/integrações experimentais preservadas;
- `native/adblock_ffi/` — FFI anterior para uma árvore Chromium completa;
- `scripts/verify_project.py` — validação rápida do contrato do projeto.
