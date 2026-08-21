#ifndef CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_ENGINE_H_
#define CHROME_BROWSER_NAUTRIX_ADBLOCK_NAUTRIX_ADBLOCK_ENGINE_H_

#include <string>
#include "base/no_destructor.h"
#include "base/synchronization/lock.h"
#include "services/network/public/cpp/resource_request.h"

namespace nautrix {
class NautrixAdBlockEngine {
 public:
  static NautrixAdBlockEngine& Get();
  enum class Action { kAllow, kBlock };
  Action Evaluate(network::ResourceRequest* request);
  bool ReplaceRules(const std::string& rules);
 private:
  friend class base::NoDestructor<NautrixAdBlockEngine>;
  NautrixAdBlockEngine();
  ~NautrixAdBlockEngine();
  std::string ResourceType(const network::ResourceRequest& request) const;
  base::Lock lock_;
  void* handle_ = nullptr;
};
}  // namespace nautrix
#endif
