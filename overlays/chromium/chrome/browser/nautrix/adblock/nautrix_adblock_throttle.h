#ifndef CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_THROTTLE_H_
#define CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_THROTTLE_H_
#include "third_party/blink/public/common/loader/url_loader_throttle.h"
namespace network { struct ResourceRequest; }
namespace nautrix {
class NautrixAdBlockThrottle final : public blink::URLLoaderThrottle {
 public:
  NautrixAdBlockThrottle();
  ~NautrixAdBlockThrottle() override;
  void WillStartRequest(network::ResourceRequest* request, bool* defer) override;
};
}  // namespace nautrix
#endif
