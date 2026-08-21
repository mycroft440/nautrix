#include "chrome/browser/nautrix/adblock/nautrix_adblock_engine.h"

#include <jni.h>
#include <utility>
#include "base/android/jni_string.h"
#include "base/no_destructor.h"
#include "services/network/public/mojom/fetch_api.mojom-shared.h"
#if NAUTRIX_HAS_ADBLOCK_RUST
#include "chrome/browser/nautrix/adblock/nautrix_adblock_ffi.h"
#endif

namespace nautrix {
namespace {
constexpr char kBootstrapRules[] =
    "||doubleclick.net^\n"
    "||googlesyndication.com^\n"
    "||googleadservices.com^\n"
    "||adservice.google.com^\n"
    "||ads-twitter.com^\n";
}

NautrixAdBlockEngine& NautrixAdBlockEngine::Get() {
  static base::NoDestructor<NautrixAdBlockEngine> instance;
  return *instance;
}

NautrixAdBlockEngine::NautrixAdBlockEngine() {
#if NAUTRIX_HAS_ADBLOCK_RUST
  handle_ = nautrix_adblock_create(kBootstrapRules);
#endif
}
NautrixAdBlockEngine::~NautrixAdBlockEngine() {
#if NAUTRIX_HAS_ADBLOCK_RUST
  if (handle_) nautrix_adblock_destroy(handle_);
#endif
}

bool NautrixAdBlockEngine::ReplaceRules(const std::string& rules) {
#if NAUTRIX_HAS_ADBLOCK_RUST
  base::AutoLock guard(lock_);
  return handle_ && nautrix_adblock_replace_rules(handle_, rules.c_str());
#else
  return false;
#endif
}

std::string NautrixAdBlockEngine::ResourceType(const network::ResourceRequest& request) const {
  using D = network::mojom::RequestDestination;
  switch (request.destination) {
    case D::kDocument: return "document";
    case D::kFrame: case D::kIframe: case D::kFencedframe: return "subdocument";
    case D::kScript: case D::kServiceWorker: case D::kSharedWorker: case D::kWorker:
    case D::kAudioWorklet: case D::kPaintWorklet: return "script";
    case D::kStyle: return "stylesheet";
    case D::kImage: return "image";
    case D::kFont: return "font";
    case D::kAudio: case D::kVideo: case D::kTrack: return "media";
    case D::kEmpty: return "xmlhttprequest";
    default: return "other";
  }
}

NautrixAdBlockEngine::Action NautrixAdBlockEngine::Evaluate(
    network::ResourceRequest* request) {
#if NAUTRIX_HAS_ADBLOCK_RUST
  if (!request) return Action::kAllow;
  std::string source;
  if (request->request_initiator) source = request->request_initiator->GetURL().spec();
  else if (request->referrer.is_valid()) source = request->referrer.spec();
  else source = request->url.GetOrigin().spec();
  const std::string type = ResourceType(*request);
  char* rewritten = nullptr;
  int32_t decision = 0;
  {
    base::AutoLock guard(lock_);
    if (handle_) {
      decision = nautrix_adblock_check_network_request(
          handle_, request->url.spec().c_str(), source.c_str(), type.c_str(),
          request->method.c_str(), &rewritten);
    }
  }
  if (decision == 1) return Action::kBlock;
  if (decision == 2 && rewritten) {
    GURL sanitized(rewritten);
    nautrix_adblock_string_free(rewritten);
    if (sanitized.is_valid() && sanitized.SchemeIsHTTPOrHTTPS())
      request->url = std::move(sanitized);
  } else if (rewritten) {
    nautrix_adblock_string_free(rewritten);
  }
  return Action::kAllow;
#else
  return Action::kAllow;
#endif
}

}  // namespace nautrix

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_adblock_NautrixAdBlockBridge_nativeReplaceRules(
    JNIEnv* env, jclass clazz, jstring rules) {
  return nautrix::NautrixAdBlockEngine::Get().ReplaceRules(
      base::android::ConvertJavaStringToUTF8(env, rules));
}
