#include "chrome/browser/nautrix/adblock/nautrix_adblock_throttle.h"
#include "chrome/browser/nautrix/adblock/nautrix_adblock_engine.h"
#include "net/base/net_errors.h"
#include "services/network/public/cpp/resource_request.h"
namespace nautrix {
NautrixAdBlockThrottle::NautrixAdBlockThrottle() = default;
NautrixAdBlockThrottle::~NautrixAdBlockThrottle() = default;
void NautrixAdBlockThrottle::WillStartRequest(network::ResourceRequest* request, bool* defer) {
  if (!request || !delegate_) return;
  if (NautrixAdBlockEngine::Get().Evaluate(request) ==
      NautrixAdBlockEngine::Action::kBlock) {
    delegate_->CancelWithError(net::ERR_BLOCKED_BY_CLIENT, "Nautrix adblock");
  }
}
}  // namespace nautrix
