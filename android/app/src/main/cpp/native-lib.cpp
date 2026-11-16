// 内嵌 Node 的 JNI 入口：把 Java 传来的参数和环境变量交给 node::Start。
// 参考 nodejs-mobile 官方 Android 示例；Node 在一个进程里只能启动一次。
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

namespace {

const char *kTag = "hongguo-node";
int pipeStdout[2];
int pipeStderr[2];

// Node 的 stdout / stderr 转进 logcat，方便用 `adb logcat -s hongguo-node` 排查。
void *pumpPipe(void *arg) {
  int fd = *static_cast<int *>(arg);
  int prio = fd == pipeStderr[0] ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
  char buf[2048];
  ssize_t n;
  while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
    if (buf[n - 1] == '\n') --n;
    buf[n] = 0;
    __android_log_write(prio, kTag, buf);
  }
  return nullptr;
}

void redirectStdio() {
  setvbuf(stdout, nullptr, _IOLBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);
  pipe(pipeStdout);
  pipe(pipeStderr);
  dup2(pipeStdout[1], STDOUT_FILENO);
  dup2(pipeStderr[1], STDERR_FILENO);
  pthread_t t1, t2;
  pthread_create(&t1, nullptr, pumpPipe, &pipeStdout[0]);
  pthread_create(&t2, nullptr, pumpPipe, &pipeStderr[0]);
  pthread_detach(t1);
  pthread_detach(t2);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_hongguoweb_app_NodeRunner_startNode(JNIEnv *env, jclass, jobjectArray args, jobjectArray envs) {
  // 先写环境变量（KEY=VALUE），Node 启动后通过 process.env 读取。
  jsize envCount = env->GetArrayLength(envs);
  for (jsize i = 0; i < envCount; i++) {
    auto item = static_cast<jstring>(env->GetObjectArrayElement(envs, i));
    const char *kv = env->GetStringUTFChars(item, nullptr);
    std::string s(kv);
    env->ReleaseStringUTFChars(item, kv);
    size_t eq = s.find('=');
    if (eq != std::string::npos) setenv(s.substr(0, eq).c_str(), s.substr(eq + 1).c_str(), 1);
  }

  // node::Start 要求 argv 各参数在一块连续内存里（libuv 会改写这块内存做进程标题）。
  jsize argc = env->GetArrayLength(args);
  std::vector<std::string> values;
  size_t total = 0;
  for (jsize i = 0; i < argc; i++) {
    auto item = static_cast<jstring>(env->GetObjectArrayElement(args, i));
    const char *a = env->GetStringUTFChars(item, nullptr);
    values.emplace_back(a);
    env->ReleaseStringUTFChars(item, a);
    total += values.back().size() + 1;
  }
  char *block = static_cast<char *>(calloc(total, 1));
  std::vector<char *> argv(argc);
  char *cursor = block;
  for (jsize i = 0; i < argc; i++) {
    memcpy(cursor, values[i].c_str(), values[i].size());
    argv[i] = cursor;
    cursor += values[i].size() + 1;
  }

  redirectStdio();
  int code = node::Start(argc, argv.data());
  free(block);
  return code;
}
