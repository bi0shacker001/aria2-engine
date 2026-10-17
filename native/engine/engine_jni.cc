// The engine process's half of the JNI layer (docs/design.md, "Layers"): the sandbox lockdown, then one libaria2
// session driven from the engine thread. libaria2 is not thread-safe, so every function here except lockdown is
// called from that one thread (EngineThread), and the download events libaria2 raises inside run() call back into
// Kotlin on it (EngineNative.onEvent).
#include <aria2/aria2.h>
#include <jni.h>

#include <android/log.h>
#include <unistd.h>

#include <cstdio>
#include <string>
#include <vector>

extern "C" {
#include "sbx.h"
}

#define TAG "aria2engine"

namespace {

JavaVM* gVm = nullptr;
jclass gNative = nullptr;
jmethodID gOnEvent = nullptr;

std::string str(JNIEnv* env, jstring value) {
  if (!value) return std::string();
  const char* chars = env->GetStringUTFChars(value, nullptr);
  std::string out(chars ? chars : "");
  if (chars) env->ReleaseStringUTFChars(value, chars);
  return out;
}

std::vector<std::string> strings(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> out;
  jsize n = array ? env->GetArrayLength(array) : 0;
  for (jsize i = 0; i < n; i++) {
    auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    out.push_back(str(env, item));
    env->DeleteLocalRef(item);
  }
  return out;
}

aria2::KeyVals keyVals(JNIEnv* env, jobjectArray keys, jobjectArray values) {
  std::vector<std::string> k = strings(env, keys), v = strings(env, values);
  aria2::KeyVals out;
  for (size_t i = 0; i < k.size() && i < v.size(); i++) out.emplace_back(k[i], v[i]);
  return out;
}

jstring utf(JNIEnv* env, const std::string& value) { return env->NewStringUTF(value.c_str()); }

// A JSON string literal for value (the paths and names libaria2 reports).
std::string quote(const std::string& value) {
  std::string out = "\"";
  for (unsigned char c : value) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      case '\t': out += "\\t"; break;
      default:
        if (c < 0x20) {
          char buffer[8];
          std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
          out += buffer;
        } else {
          out += static_cast<char>(c);
        }
    }
  }
  return out + "\"";
}

aria2::Session* session(jlong handle) { return reinterpret_cast<aria2::Session*>(handle); }

int onEvent(aria2::Session*, aria2::DownloadEvent event, aria2::A2Gid gid, void*) {
  JNIEnv* env = nullptr;
  if (!gVm || gVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK || !env) return 0;
  jstring hex = utf(env, aria2::gidToHex(gid));
  env->CallStaticVoidMethod(gNative, gOnEvent, static_cast<jint>(event), hex);
  if (env->ExceptionCheck()) env->ExceptionClear();
  env->DeleteLocalRef(hex);
  return 0;
}

const char* statusName(aria2::DownloadStatus status) {
  switch (status) {
    case aria2::DOWNLOAD_ACTIVE: return "active";
    case aria2::DOWNLOAD_WAITING: return "waiting";
    case aria2::DOWNLOAD_PAUSED: return "paused";
    case aria2::DOWNLOAD_COMPLETE: return "complete";
    case aria2::DOWNLOAD_ERROR: return "error";
    case aria2::DOWNLOAD_REMOVED: return "removed";
  }
  return "unknown";
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  gVm = vm;
  JNIEnv* env = nullptr;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
  jclass local = env->FindClass("io/github/bi0shacker001/aria2engine/EngineNative");
  if (!local) return JNI_ERR;
  gNative = static_cast<jclass>(env->NewGlobalRef(local));
  gOnEvent = env->GetStaticMethodID(gNative, "onEvent", "(ILjava/lang/String;)V");
  return gOnEvent ? JNI_VERSION_1_6 : JNI_ERR;
}

// Confines this process before libaria2 runs (docs/design.md, "Files" and "Network"): every path under a claim goes
// to the broker in the host's process over brokerFd, and with brokeredNet the internet sockets are the broker's.
// Returns "on" or "error: <why>"; the service refuses to start a session on an error.
JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_lockdown(
    JNIEnv* env, jclass, jint brokerFd, jobjectArray claims, jboolean brokeredNet) {
  std::vector<std::string> claimed = strings(env, claims);
  std::vector<const char*> pointers;
  for (auto& claim : claimed) pointers.push_back(claim.c_str());
  sbx_lockdown_options options{};
  options.flags = SBX_LOCK_LOG_SOCKET | SBX_LOCK_PASSTHROUGH | (brokeredNet ? SBX_LOCK_NET_BROKERED : 0u);
  options.claims = pointers.data();
  options.claim_count = static_cast<unsigned>(pointers.size());
  int result = sbx_lockdown(brokerFd, &options);
  if (result != 0) {
    char buffer[160];
    std::snprintf(buffer, sizeof(buffer), "error: the lockdown was refused (%d)", result);
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", buffer);
    return utf(env, buffer);
  }
  return utf(env, "on");
}

JNIEXPORT jlong JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_sessionNew(
    JNIEnv* env, jclass, jobjectArray keys, jobjectArray values) {
  aria2::libraryInit();
  aria2::SessionConfig config;
  config.keepRunning = true;
  config.useSignalHandler = false;
  config.downloadEventCallback = onEvent;
  return reinterpret_cast<jlong>(aria2::sessionNew(keyVals(env, keys, values), config));
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_runOnce(JNIEnv*, jclass, jlong handle) {
  return aria2::run(session(handle), aria2::RUN_ONCE);
}

// The gid as 16 hex digits, or "error:<libaria2's code>".
JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_addUri(
    JNIEnv* env, jclass, jlong handle, jobjectArray uris, jobjectArray keys, jobjectArray values) {
  aria2::A2Gid gid = 0;
  int rv = aria2::addUri(session(handle), &gid, strings(env, uris), keyVals(env, keys, values));
  return utf(env, rv < 0 ? "error:" + std::to_string(rv) : aria2::gidToHex(gid));
}

JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_addTorrent(
    JNIEnv* env, jclass, jlong handle, jstring path, jobjectArray keys, jobjectArray values) {
  aria2::A2Gid gid = 0;
  int rv = aria2::addTorrent(session(handle), &gid, str(env, path), keyVals(env, keys, values));
  return utf(env, rv < 0 ? "error:" + std::to_string(rv) : aria2::gidToHex(gid));
}

// The gids, comma-separated, or "error:<code>".
JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_addMetalink(
    JNIEnv* env, jclass, jlong handle, jstring path, jobjectArray keys, jobjectArray values) {
  std::vector<aria2::A2Gid> gids;
  int rv = aria2::addMetalink(session(handle), &gids, str(env, path), keyVals(env, keys, values));
  if (rv < 0) return utf(env, "error:" + std::to_string(rv));
  std::string out;
  for (auto gid : gids) out += (out.empty() ? "" : ",") + aria2::gidToHex(gid);
  return utf(env, out);
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_pause(
    JNIEnv* env, jclass, jlong handle, jstring gid, jboolean force) {
  return aria2::pauseDownload(session(handle), aria2::hexToGid(str(env, gid)), force);
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_unpause(
    JNIEnv* env, jclass, jlong handle, jstring gid) {
  return aria2::unpauseDownload(session(handle), aria2::hexToGid(str(env, gid)));
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_remove(
    JNIEnv* env, jclass, jlong handle, jstring gid, jboolean force) {
  return aria2::removeDownload(session(handle), aria2::hexToGid(str(env, gid)), force);
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_changeOption(
    JNIEnv* env, jclass, jlong handle, jstring gid, jobjectArray keys, jobjectArray values) {
  return aria2::changeOption(session(handle), aria2::hexToGid(str(env, gid)), keyVals(env, keys, values));
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_changeGlobalOption(
    JNIEnv* env, jclass, jlong handle, jobjectArray keys, jobjectArray values) {
  return aria2::changeGlobalOption(session(handle), keyVals(env, keys, values));
}

// Every download the session knows that is active, waiting or paused, as gids, comma-separated.
JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_activeDownloads(
    JNIEnv* env, jclass, jlong handle) {
  std::string out;
  for (auto gid : aria2::getActiveDownload(session(handle))) out += (out.empty() ? "" : ",") + aria2::gidToHex(gid);
  return utf(env, out);
}

// One download's state as JSON, or null when the session no longer knows it. Typed facts only: the host never
// reads the downloaded bytes to learn about a download.
JNIEXPORT jstring JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_status(
    JNIEnv* env, jclass, jlong handle, jstring gidHex) {
  aria2::DownloadHandle* h = aria2::getDownloadHandle(session(handle), aria2::hexToGid(str(env, gidHex)));
  if (!h) return nullptr;
  std::string json = "{\"status\":" + quote(statusName(h->getStatus()));
  json += ",\"totalLength\":" + std::to_string(h->getTotalLength());
  json += ",\"completedLength\":" + std::to_string(h->getCompletedLength());
  json += ",\"uploadLength\":" + std::to_string(h->getUploadLength());
  json += ",\"downloadSpeed\":" + std::to_string(h->getDownloadSpeed());
  json += ",\"uploadSpeed\":" + std::to_string(h->getUploadSpeed());
  json += ",\"connections\":" + std::to_string(h->getConnections());
  json += ",\"errorCode\":" + std::to_string(h->getErrorCode());
  std::string followed;
  for (auto gid : h->getFollowedBy()) followed += (followed.empty() ? "" : ",") + quote(aria2::gidToHex(gid));
  json += ",\"followedBy\":[" + followed + "]";
  json += ",\"following\":" + quote(h->getFollowing() ? aria2::gidToHex(h->getFollowing()) : "");
  json += ",\"dir\":" + quote(h->getDir());
  std::string files;
  for (int i = 1; i <= h->getNumFiles(); i++) {
    aria2::FileData file = h->getFile(i);
    files += files.empty() ? "" : ",";
    files += "{\"index\":" + std::to_string(file.index) + ",\"path\":" + quote(file.path) +
             ",\"length\":" + std::to_string(file.length) + ",\"completedLength\":" +
             std::to_string(file.completedLength) + ",\"selected\":" + (file.selected ? "true" : "false") + "}";
  }
  json += ",\"files\":[" + files + "]";
  aria2::BtMetaInfoData bt = h->getBtMetaInfo();
  json += ",\"torrentName\":" + quote(bt.name) + "}";
  aria2::deleteDownloadHandle(h);
  return utf(env, json);
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_shutdown(
    JNIEnv*, jclass, jlong handle, jboolean force) {
  return aria2::shutdown(session(handle), force);
}

JNIEXPORT jint JNICALL Java_io_github_bi0shacker001_aria2engine_EngineNative_sessionFinal(JNIEnv*, jclass, jlong handle) {
  int rv = aria2::sessionFinal(session(handle));
  aria2::libraryDeinit();
  return rv;
}

}  // extern "C"
