#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "base/android/jni_string.h"
#include "base/json/json_writer.h"
#include "base/values.h"

#if NAUTRIX_HAS_LIBTORRENT
#include "libtorrent/add_torrent_params.hpp"
#include "libtorrent/download_priority.hpp"
#include "libtorrent/error_code.hpp"
#include "libtorrent/magnet_uri.hpp"
#include "libtorrent/read_resume_data.hpp"
#include "libtorrent/session.hpp"
#include "libtorrent/settings_pack.hpp"
#include "libtorrent/span.hpp"
#include "libtorrent/torrent_flags.hpp"
#include "libtorrent/torrent_handle.hpp"
#include "libtorrent/torrent_info.hpp"
#include "libtorrent/torrent_status.hpp"
#include "libtorrent/write_resume_data.hpp"
#endif

namespace nautrix {
#if NAUTRIX_HAS_LIBTORRENT
namespace lt = libtorrent;
namespace fs = std::filesystem;

class TorrentSession {
 public:
  TorrentSession(std::string save_path, std::string resume_path)
      : save_path_(std::move(save_path)), resume_path_(std::move(resume_path)) {
    std::error_code fs_ec;
    fs::create_directories(save_path_, fs_ec);
    fs_ec.clear();
    fs::create_directories(resume_path_, fs_ec);

    lt::settings_pack settings = lt::min_memory_usage();
    settings.set_bool(lt::settings_pack::enable_dht, true);
    settings.set_bool(lt::settings_pack::enable_lsd, true);
    settings.set_bool(lt::settings_pack::enable_upnp, true);
    settings.set_bool(lt::settings_pack::enable_natpmp, true);
    settings.set_int(lt::settings_pack::connections_limit, 200);
    session_.apply_settings(settings);
    LoadResumeState();
  }

  ~TorrentSession() { SaveResumeState(); }

  std::string AddMagnet(const std::string& magnet) {
    std::scoped_lock lock(mutex_);
    lt::error_code ec;
    auto params = lt::parse_magnet_uri(magnet, ec);
    if (ec) return {};
    PrepareNewTorrent(params);
    auto handle = session_.add_torrent(std::move(params), ec);
    if (ec || !handle.is_valid()) return {};
    return Store(handle);
  }

  std::string AddTorrentFile(const std::string& path) {
    std::scoped_lock lock(mutex_);
    lt::error_code ec;
    auto info = std::make_shared<lt::torrent_info>(path, ec);
    if (ec) return {};
    lt::add_torrent_params params;
    params.ti = std::move(info);
    PrepareNewTorrent(params);
    auto handle = session_.add_torrent(std::move(params), ec);
    if (ec || !handle.is_valid()) return {};
    return Store(handle);
  }

  bool Pause(const std::string& id) {
    std::scoped_lock lock(mutex_);
    auto* h = Find(id);
    if (!h || !h->is_valid()) return false;
    // auto_managed torrents may be resumed by libtorrent's queue. User/policy pause must stick.
    h->unset_flags(lt::torrent_flags::auto_managed);
    h->pause();
    return true;
  }

  bool Resume(const std::string& id) {
    std::scoped_lock lock(mutex_);
    auto* h = Find(id);
    if (!h || !h->is_valid()) return false;
    h->set_flags(lt::torrent_flags::auto_managed);
    h->resume();
    return true;
  }

  bool Sequential(const std::string& id, bool enabled) {
    std::scoped_lock lock(mutex_);
    auto* h = Find(id);
    if (!h || !h->is_valid()) return false;
    // The flag API is the supported path when deprecated-functions=OFF.
    if (enabled) {
      h->set_flags(lt::torrent_flags::sequential_download);
    } else {
      h->unset_flags(lt::torrent_flags::sequential_download);
    }
    return true;
  }

  void SetRateLimits(int down, int up) {
    std::scoped_lock lock(mutex_);
    lt::settings_pack settings;
    settings.set_int(lt::settings_pack::download_rate_limit, std::max(0, down));
    settings.set_int(lt::settings_pack::upload_rate_limit, std::max(0, up));
    session_.apply_settings(settings);
  }

  void SetConnectionLimit(int max_connections) {
    std::scoped_lock lock(mutex_);
    lt::settings_pack settings;
    settings.set_int(lt::settings_pack::connections_limit,
                     std::clamp(max_connections, 20, 2000));
    session_.apply_settings(settings);
  }

  std::string FilesJson(const std::string& id) {
    std::scoped_lock lock(mutex_);
    auto* h = Find(id);
    if (!h || !h->is_valid()) return "[]";
    auto info = h->torrent_file();
    if (!info) return "[]";  // Magnet metadata may not have arrived yet.

    auto priorities = h->get_file_priorities();
    const auto& files = info->files();
    base::Value::List list;
    for (lt::file_index_t index : files.file_range()) {
      const int raw_index = static_cast<int>(index);
      base::Value::Dict item;
      item.Set("index", raw_index);
      item.Set("path", files.file_path(index));
      item.Set("size", static_cast<double>(files.file_size(index)));
      item.Set("pad", files.pad_file_at(index));
      int priority = 0;
      if (raw_index >= 0 && static_cast<size_t>(raw_index) < priorities.size()) {
        priority = static_cast<int>(priorities[raw_index]);
      }
      item.Set("priority", priority);
      item.Set("selected", priority != static_cast<int>(lt::dont_download));
      list.Append(std::move(item));
    }
    std::string out;
    base::JSONWriter::Write(list, &out);
    return out;
  }

  bool SetFilePriority(const std::string& id, int file_index, int priority) {
    std::scoped_lock lock(mutex_);
    auto* h = Find(id);
    if (!h || !h->is_valid() || file_index < 0) return false;
    auto info = h->torrent_file();
    if (!info || file_index >= static_cast<int>(info->files().end_file())) return false;

    lt::download_priority_t mapped = lt::default_priority;
    if (priority <= 0) {
      mapped = lt::dont_download;
    } else if (priority >= 7) {
      mapped = lt::top_priority;
    } else {
      mapped = lt::download_priority_t{static_cast<std::uint8_t>(priority)};
    }
    h->file_priority(lt::file_index_t{file_index}, mapped);
    return true;
  }

  bool Remove(const std::string& id, bool delete_data) {
    std::scoped_lock lock(mutex_);
    int64_t numeric_id = 0;
    if (!ParseId(id, &numeric_id)) return false;
    auto it = torrents_.find(numeric_id);
    if (it == torrents_.end()) return false;

    if (it->second.is_valid()) {
      session_.remove_torrent(
          it->second,
          delete_data ? lt::session_handle::delete_files : lt::remove_flags_t{});
    }
    torrents_.erase(it);
    std::error_code ec;
    fs::remove(ResumeFile(numeric_id), ec);
    fs::remove(ResumeTempFile(numeric_id), ec);
    return true;
  }

  void SaveResumeState() {
    std::scoped_lock lock(mutex_);
    for (const auto& [id, handle] : torrents_) {
      if (!handle.is_valid()) continue;
      // Persist a complete libtorrent 2.1 fast-resume snapshot in app-private storage.
      auto params = handle.get_resume_data(lt::torrent_handle::save_info_dict);
      params.save_path = save_path_;
      WriteResume(id, params);
    }
  }

  std::string StatusJson() {
    std::scoped_lock lock(mutex_);
    base::Value::List list;
    for (auto it = torrents_.begin(); it != torrents_.end();) {
      if (!it->second.is_valid()) {
        it = torrents_.erase(it);
        continue;
      }
      auto status = it->second.status();
      base::Value::Dict item;
      item.Set("id", std::to_string(it->first));
      item.Set("name", status.name);
      item.Set("progressPpm", status.progress_ppm);
      item.Set("downloadRate", status.download_rate);
      item.Set("uploadRate", status.upload_rate);
      item.Set("downloaded", static_cast<double>(status.total_done));
      item.Set("wanted", static_cast<double>(status.total_wanted));
      item.Set("peers", status.num_peers);
      item.Set("seeds", status.num_seeds);
      item.Set("paused", status.paused);
      item.Set("finished", status.is_finished);
      item.Set("hasMetadata", static_cast<bool>(it->second.torrent_file()));
      item.Set("sequential",
               static_cast<bool>(it->second.flags() & lt::torrent_flags::sequential_download));
      list.Append(std::move(item));
      ++it;
    }
    std::string out;
    base::JSONWriter::Write(list, &out);
    return out;
  }

 private:
  static void PrepareNewTorrent(lt::add_torrent_params& params) {
    // Keep the paused + auto_managed queue semantics from libtorrent's defaults. Auto-managed
    // torrents are intentionally added paused and the queue manager starts them when capacity is
    // available. Clearing paused here bypasses queue limits and can create connection bursts.
    params.flags &= ~lt::torrent_flags::duplicate_is_error;
    params.flags |= lt::torrent_flags::auto_managed;
    params.flags |= lt::torrent_flags::paused;
  }

  std::string Store(const lt::torrent_handle& h, int64_t preferred_id = 0) {
    // Libtorrent returns the existing handle when duplicate_is_error is unset.
    for (const auto& [id, existing] : torrents_) {
      if (existing == h) return std::to_string(id);
    }

    int64_t id = preferred_id;
    if (id <= 0 || torrents_.find(id) != torrents_.end()) id = next_id_;
    next_id_ = std::max(next_id_, id + 1);
    torrents_.emplace(id, h);
    return std::to_string(id);
  }

  lt::torrent_handle* Find(const std::string& raw) {
    int64_t id = 0;
    if (!ParseId(raw, &id)) return nullptr;
    auto it = torrents_.find(id);
    return it == torrents_.end() ? nullptr : &it->second;
  }

  static bool ParseId(const std::string& raw, int64_t* id) {
    if (!id || raw.empty()) return false;
    int64_t value = 0;
    for (char c : raw) {
      if (c < '0' || c > '9') return false;
      if (value > (std::numeric_limits<int64_t>::max() - (c - '0')) / 10) return false;
      value = value * 10 + (c - '0');
    }
    if (value <= 0) return false;
    *id = value;
    return true;
  }

  fs::path ResumeFile(int64_t id) const {
    return fs::path(resume_path_) / (std::to_string(id) + ".fastresume");
  }

  fs::path ResumeTempFile(int64_t id) const {
    return fs::path(resume_path_) / (std::to_string(id) + ".fastresume.tmp");
  }

  void WriteResume(int64_t id, const lt::add_torrent_params& params) {
    const std::vector<char> bytes = lt::write_resume_data_buf(params);
    if (bytes.empty()) return;

    const fs::path target = ResumeFile(id);
    const fs::path temp = ResumeTempFile(id);
    {
      std::ofstream out(temp, std::ios::binary | std::ios::trunc);
      if (!out) return;
      out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
      out.flush();
      if (!out) return;
    }

    std::error_code ec;
    fs::rename(temp, target, ec);
    if (ec) {
      ec.clear();
      fs::remove(target, ec);
      ec.clear();
      fs::rename(temp, target, ec);
      if (ec) fs::remove(temp, ec);
    }
  }

  void LoadResumeState() {
    std::error_code fs_ec;
    if (!fs::is_directory(resume_path_, fs_ec)) return;

    for (fs::directory_iterator it(resume_path_, fs_ec), end; !fs_ec && it != end;
         it.increment(fs_ec)) {
      if (!it->is_regular_file(fs_ec) || it->path().extension() != ".fastresume") continue;

      int64_t preferred_id = 0;
      if (!ParseId(it->path().stem().string(), &preferred_id)) continue;

      std::ifstream in(it->path(), std::ios::binary);
      if (!in) continue;
      std::vector<char> bytes((std::istreambuf_iterator<char>(in)),
                              std::istreambuf_iterator<char>());
      if (bytes.empty()) continue;

      lt::error_code ec;
      auto params = lt::read_resume_data(lt::span<char const>(bytes.data(), bytes.size()), ec);
      if (ec) continue;
      // Resume files are trusted only because they live under getFilesDir(). Still override the
      // most security-sensitive path so stale/corrupt data cannot escape Nautrix's payload root.
      params.save_path = save_path_;
      params.flags &= ~lt::torrent_flags::duplicate_is_error;
      auto handle = session_.add_torrent(std::move(params), ec);
      if (!ec && handle.is_valid()) Store(handle, preferred_id);
    }
  }

  std::string save_path_;
  std::string resume_path_;
  std::mutex mutex_;
  lt::session session_;
  int64_t next_id_ = 1;
  std::map<int64_t, lt::torrent_handle> torrents_;
};
#endif
}  // namespace nautrix

namespace {
#if NAUTRIX_HAS_LIBTORRENT
nautrix::TorrentSession* From(jlong handle) {
  return reinterpret_cast<nautrix::TorrentSession*>(handle);
}
#endif
jstring JString(JNIEnv* env, const std::string& value) {
  return value.empty() ? nullptr : env->NewStringUTF(value.c_str());
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeCreate(
    JNIEnv* env, jclass, jstring save_path, jstring resume_path) {
#if NAUTRIX_HAS_LIBTORRENT
  return reinterpret_cast<jlong>(new nautrix::TorrentSession(
      base::android::ConvertJavaStringToUTF8(env, save_path),
      base::android::ConvertJavaStringToUTF8(env, resume_path)));
#else
  return 0;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
#if NAUTRIX_HAS_LIBTORRENT
  delete From(handle);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeAddMagnet(
    JNIEnv* env, jclass, jlong handle, jstring magnet) {
#if NAUTRIX_HAS_LIBTORRENT
  if (!handle) return nullptr;
  return JString(env, From(handle)->AddMagnet(
                          base::android::ConvertJavaStringToUTF8(env, magnet)));
#else
  return nullptr;
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeAddTorrentFile(
    JNIEnv* env, jclass, jlong handle, jstring path) {
#if NAUTRIX_HAS_LIBTORRENT
  if (!handle) return nullptr;
  return JString(env, From(handle)->AddTorrentFile(
                          base::android::ConvertJavaStringToUTF8(env, path)));
#else
  return nullptr;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativePause(
    JNIEnv* env, jclass, jlong handle, jstring id) {
#if NAUTRIX_HAS_LIBTORRENT
  return handle && From(handle)->Pause(base::android::ConvertJavaStringToUTF8(env, id));
#else
  return false;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeResume(
    JNIEnv* env, jclass, jlong handle, jstring id) {
#if NAUTRIX_HAS_LIBTORRENT
  return handle && From(handle)->Resume(base::android::ConvertJavaStringToUTF8(env, id));
#else
  return false;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeSetSequential(
    JNIEnv* env, jclass, jlong handle, jstring id, jboolean enabled) {
#if NAUTRIX_HAS_LIBTORRENT
  return handle && From(handle)->Sequential(
                       base::android::ConvertJavaStringToUTF8(env, id), enabled);
#else
  return false;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeSetRateLimits(
    JNIEnv*, jclass, jlong handle, jint down, jint up) {
#if NAUTRIX_HAS_LIBTORRENT
  if (handle) From(handle)->SetRateLimits(down, up);
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeSetConnectionLimit(
    JNIEnv*, jclass, jlong handle, jint max_connections) {
#if NAUTRIX_HAS_LIBTORRENT
  if (handle) From(handle)->SetConnectionLimit(max_connections);
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeFilesJson(
    JNIEnv* env, jclass, jlong handle, jstring id) {
#if NAUTRIX_HAS_LIBTORRENT
  if (!handle) return env->NewStringUTF("[]");
  const std::string out = From(handle)->FilesJson(
      base::android::ConvertJavaStringToUTF8(env, id));
  return env->NewStringUTF(out.c_str());
#else
  return env->NewStringUTF("[]");
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeSetFilePriority(
    JNIEnv* env, jclass, jlong handle, jstring id, jint file_index, jint priority) {
#if NAUTRIX_HAS_LIBTORRENT
  return handle && From(handle)->SetFilePriority(
                       base::android::ConvertJavaStringToUTF8(env, id), file_index,
                       priority);
#else
  return false;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeRemove(
    JNIEnv* env, jclass, jlong handle, jstring id, jboolean delete_data) {
#if NAUTRIX_HAS_LIBTORRENT
  return handle && From(handle)->Remove(
                       base::android::ConvertJavaStringToUTF8(env, id), delete_data);
#else
  return false;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeSaveResumeState(
    JNIEnv*, jclass, jlong handle) {
#if NAUTRIX_HAS_LIBTORRENT
  if (handle) From(handle)->SaveResumeState();
#endif
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_chromium_chrome_browser_nautrix_torrent_NautrixTorrentService_nativeStatusJson(
    JNIEnv* env, jclass, jlong handle) {
#if NAUTRIX_HAS_LIBTORRENT
  const std::string out = handle ? From(handle)->StatusJson() : "[]";
  return env->NewStringUTF(out.c_str());
#else
  return env->NewStringUTF("[]");
#endif
}
