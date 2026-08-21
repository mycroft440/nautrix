#include <jni.h>

#include "base/android/scoped_java_ref.h"

#include "components/download/public/common/download_item.h"
#include "components/download/public/common/simple_download_manager.h"
#include "content/public/browser/browser_context.h"
#include "content/public/browser/download_item_utils.h"
#include "content/public/browser/download_manager.h"
#include "content/public/browser/web_contents.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_download_NautrixDownloadProtectionBridge_nativeHasActiveDownload(
    JNIEnv* env, jclass, jobject jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(
          base::android::JavaRef<jobject>(env, jweb_contents));
  if (!web_contents) return false;
  content::DownloadManager* manager =
      web_contents->GetBrowserContext()->GetDownloadManager();
  if (!manager) return false;

  download::SimpleDownloadManager::DownloadVector downloads;
  manager->GetAllDownloads(&downloads);
  manager->GetUninitializedActiveDownloadsIfAny(&downloads);
  for (download::DownloadItem* item : downloads) {
    if (!item || item->GetState() != download::DownloadItem::IN_PROGRESS) continue;
    if (content::DownloadItemUtils::GetWebContents(item) == web_contents) return true;
  }
  return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chromium_chrome_browser_nautrix_download_NautrixDownloadProtectionBridge_nativeHasPotentialUnsavedState(
    JNIEnv* env, jclass, jobject jweb_contents) {
  content::WebContents* web_contents =
      content::WebContents::FromJavaWebContents(
          base::android::JavaRef<jobject>(env, jweb_contents));
  return web_contents && web_contents->NeedToFireBeforeUnloadOrUnloadEvents();
}
