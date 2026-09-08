#pragma once
// Host-only adapters: no real Android/JNI runtime is used. Native data/socket
// functions are unchanged; one receive-loop test uses mock thread attachment.
#include <cstdint>
#include <vector>
#include <cstring>
using jint=int; using jsize=int; using jlong=long long; using jbyte=signed char;
using jboolean=unsigned char; using jobject=void*; using jclass=void*;
using jstring=void*; using jbyteArray=void*; using jlongArray=void*;
using jintArray=void*; using jarray=void*; using jmethodID=void*; using jfieldID=void*;
#define JNIEXPORT
#define JNICALL
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_OK 0
struct JavaVM;
struct JNIEnv {
  // Explicit mock callback data, only used to exercise native success/failure
  // branches. This is NOT an execution of the Kotlin STUN fabricator.
  std::vector<jbyte> input_bytes;
  std::vector<jbyte> response_bytes;
  std::vector<jlong> result_longs;
  jboolean prepare_result = JNI_FALSE;
  bool prepare_throws = false;
  bool pending_exception = false;
  int prepare_calls = 0;
  int prepared_fd = -1;
  jclass FindClass(const char*) { return nullptr; }
  bool ExceptionCheck() { return pending_exception; }
  void ExceptionClear() { pending_exception = false; }
  jmethodID GetStaticMethodID(...) { return nullptr; }
  jfieldID GetStaticFieldID(...) { return nullptr; }
  jint CallStaticIntMethod(...) { return 0; }
  jint GetStaticIntField(...) { return 0; }
  void CallStaticVoidMethod(...) {}
  void DeleteLocalRef(...) {}
  void DeleteGlobalRef(...) {}
  jstring NewStringUTF(...) { return nullptr; }
  void CallVoidMethod(...) {}
  jbyteArray NewByteArray(jsize size) { input_bytes.resize(size); return &input_bytes; }
  void SetByteArrayRegion(jbyteArray array, jsize offset, jsize length, const jbyte* bytes) {
    auto* value = static_cast<std::vector<jbyte>*>(array);
    std::memcpy(value->data() + offset, bytes, length);
  }
  jobject CallObjectMethod(...) { return response_bytes.empty() ? nullptr : &response_bytes; }
  jboolean CallBooleanMethod(jobject, jmethodID, jint fd) {
    ++prepare_calls; prepared_fd = fd; pending_exception = prepare_throws;
    return prepare_result;
  }
  jsize GetArrayLength(jarray array) { return static_cast<std::vector<jbyte>*>(array)->size(); }
  void GetByteArrayRegion(jbyteArray array, jsize offset, jsize length, jbyte* bytes) {
    const auto* value = static_cast<std::vector<jbyte>*>(array);
    std::memcpy(bytes, value->data() + offset, length);
  }
  const char* GetStringUTFChars(...) { return ""; }
  void ReleaseStringUTFChars(...) {}
  jlongArray NewLongArray(jsize size) { result_longs.assign(size, 0); return &result_longs; }
  jintArray NewIntArray(...) { return nullptr; }
  void SetLongArrayRegion(jlongArray array, jsize offset, jsize length, const jlong* values) {
    auto* result = static_cast<std::vector<jlong>*>(array);
    std::memcpy(result->data() + offset, values, length * sizeof(jlong));
  }
  void SetIntArrayRegion(...) {}
  jint GetJavaVM(JavaVM**) { return -1; }
  jobject NewGlobalRef(...) { return nullptr; }
  jclass GetObjectClass(...) { return nullptr; }
  jmethodID GetMethodID(...) { return nullptr; }
};
struct JavaVM {
  bool enable_host_thread = false;
  JNIEnv host_env;
  jint AttachCurrentThread(JNIEnv** out, void*) {
    if (!enable_host_thread) return -1;
    *out = &host_env;
    return 0;
  }
  jint DetachCurrentThread() { return 0; }
};
