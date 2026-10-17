// Drives libaria2 the way the engine service will: one session, one download, run to completion, events counted.
//
//   engine_smoke <uri> <dir> [<expected-bytes>]
//
// Exits 0 when the download completed (and, given <expected-bytes>, the file in <dir> has that size). It prints
// the events it saw and libaria2's error code otherwise. Certificates come from SSL_CERT_DIR (on Android,
// /system/etc/security/cacerts, which is already in OpenSSL's hashed-directory form).
#include <aria2/aria2.h>

#include <sys/stat.h>

#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

namespace {

struct Seen {
  int started = 0;
  int complete = 0;
  int error = 0;
  int errorCode = 0;
  std::string file;
};

int onEvent(aria2::Session* session, aria2::DownloadEvent event, aria2::A2Gid gid, void* userData) {
  auto* seen = static_cast<Seen*>(userData);
  aria2::DownloadHandle* handle = aria2::getDownloadHandle(session, gid);
  switch (event) {
    case aria2::EVENT_ON_DOWNLOAD_START:
      seen->started++;
      break;
    case aria2::EVENT_ON_DOWNLOAD_COMPLETE:
    case aria2::EVENT_ON_BT_DOWNLOAD_COMPLETE:
      seen->complete++;
      if (handle && handle->getNumFiles() > 0) seen->file = handle->getFile(1).path;
      break;
    case aria2::EVENT_ON_DOWNLOAD_ERROR:
      seen->error++;
      if (handle) seen->errorCode = handle->getErrorCode();
      break;
    default:
      break;
  }
  if (handle) aria2::deleteDownloadHandle(handle);
  return 0;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: %s <uri> <dir> [<expected-bytes>]\n", argv[0]);
    return 2;
  }
  aria2::libraryInit();
  Seen seen;
  aria2::SessionConfig config;
  config.keepRunning = false;
  config.useSignalHandler = false;
  config.downloadEventCallback = onEvent;
  config.userData = &seen;
  aria2::KeyVals options = {{"dir", argv[2]}, {"max-tries", "3"}, {"retry-wait", "1"}, {"allow-overwrite", "true"}};
  aria2::Session* session = aria2::sessionNew(options, config);
  if (!session) {
    std::fprintf(stderr, "sessionNew failed\n");
    return 3;
  }
  aria2::A2Gid gid;
  std::vector<std::string> uris = {argv[1]};
  if (aria2::addUri(session, &gid, uris, aria2::KeyVals()) < 0) {
    std::fprintf(stderr, "addUri refused %s\n", argv[1]);
    return 4;
  }
  while (aria2::run(session, aria2::RUN_ONCE) == 1) {
  }
  aria2::sessionFinal(session);
  aria2::libraryDeinit();

  std::printf("events: started=%d complete=%d error=%d errorCode=%d file=%s\n", seen.started, seen.complete,
              seen.error, seen.errorCode, seen.file.c_str());
  if (seen.complete != 1) return 1;
  if (argc > 3) {
    struct stat st{};
    if (stat(seen.file.c_str(), &st) != 0) {
      std::printf("the completed file is not there\n");
      return 1;
    }
    long long expected = std::atoll(argv[3]);
    std::printf("size: %lld (expected %lld)\n", static_cast<long long>(st.st_size), expected);
    if (st.st_size != expected) return 1;
  }
  std::printf("ok\n");
  return 0;
}
