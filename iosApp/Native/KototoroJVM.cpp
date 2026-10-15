#include "KototoroJVM.h"
#include <cstdlib>
#include <cstring>
#if KTR_HAS_JVM
#include <jni.h>
#include <limits>
#include <fstream>
#include <mutex>
#include <string>
#include <vector>
#if KTR_HAS_NATIVE_SHIMS
extern "C" int ktr_native_install(JNIEnv *env);
#endif

struct KTRSession {
    jobject host;
    jmethodID exchange;
    jmethodID close;
};

namespace {
JavaVM *vm = nullptr;
std::mutex vm_mutex;
std::string vm_home;
std::string vm_classpath;
std::vector<std::string> vm_options;
bool vm_attempted = false;

void report(char **error, const std::string &message) {
    if (error == nullptr) return;
    *error = static_cast<char *>(std::malloc(message.size() + 1));
    if (*error != nullptr) std::memcpy(*error, message.c_str(), message.size() + 1);
}

// JNI environments and local references belong to a thread, never to the shared session.
class Environment {
    JavaVM *owner = nullptr;
public:
    JNIEnv *value = nullptr;
    bool attached = false;
    Environment() {
        {
            std::lock_guard<std::mutex> lock(vm_mutex);
            owner = vm;
        }
        if (owner == nullptr) return;
        const jint status = owner->GetEnv(reinterpret_cast<void **>(&value), JNI_VERSION_1_8);
        if (status == JNI_EDETACHED) {
            attached = owner->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&value), nullptr) == JNI_OK;
            if (!attached) value = nullptr;
        } else if (status != JNI_OK) value = nullptr;
        if (value != nullptr && value->PushLocalFrame(32) < 0) {
            value->ExceptionClear();
            if (attached) owner->DetachCurrentThread();
            attached = false;
            value = nullptr;
        }
    }
    ~Environment() {
        if (value != nullptr) value->PopLocalFrame(nullptr);
        if (attached) owner->DetachCurrentThread();
    }
};

std::string exception_message(JNIEnv *env) {
    jthrowable exception = env->ExceptionOccurred();
    if (exception == nullptr) return "Java operation failed";
    env->ExceptionClear();
    jclass type = env->GetObjectClass(exception);
    jmethodID describe = env->GetMethodID(type, "toString", "()Ljava/lang/String;");
    auto description = describe == nullptr ? nullptr :
        static_cast<jstring>(env->CallObjectMethod(exception, describe));
    if (description == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return "Unable to describe Java exception";
    }
    // NewStringUTF/GetStringUTFChars use modified UTF-8, which is not Swift's UTF-8 encoding.
    jclass string_type = env->FindClass("java/lang/String");
    if (string_type == nullptr) {
        env->ExceptionClear();
        return "Unable to load Java String";
    }
    jmethodID encode = env->GetMethodID(string_type, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = encode == nullptr ? nullptr : env->NewStringUTF("UTF-8");
    auto bytes = encode == nullptr ? nullptr :
        static_cast<jbyteArray>(env->CallObjectMethod(description, encode, charset));
    if (bytes == nullptr || env->ExceptionCheck()) {
        env->ExceptionClear();
        return "Unable to encode Java exception";
    }
    std::string message(static_cast<size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(message.size()),
                           reinterpret_cast<jbyte *>(message.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "Unable to copy Java exception";
    }
    return message;
}

bool valid_text(const uint16_t *text, size_t length) {
    return (text != nullptr || length == 0) && length <= static_cast<size_t>(std::numeric_limits<jsize>::max());
}

jstring java_string(JNIEnv *env, const uint16_t *text, size_t length) {
    static const jchar empty = 0;
    return env->NewString(text == nullptr ? &empty : reinterpret_cast<const jchar *>(text),
                          static_cast<jsize>(length));
}
}

int ktr_vm_available(void) { return 1; }

int ktr_vm_start(const char *java_home, const char *classpath,
                 const char *const *additional_options, size_t option_count, char **error) {
    if (error != nullptr) *error = nullptr;
    if (java_home == nullptr || classpath == nullptr || option_count > 64 ||
        (option_count > 0 && additional_options == nullptr)) {
        report(error, "Java home and classpath are required");
        return -1;
    }
    std::lock_guard<std::mutex> lock(vm_mutex);
    std::vector<std::string> extra;
    for (size_t index = 0; index < option_count; ++index) {
        if (additional_options[index] == nullptr) {
            report(error, "Null JVM option");
            return -1;
        }
        extra.emplace_back(additional_options[index]);
    }
    if (vm != nullptr) {
        if (vm_home == java_home && vm_classpath == classpath && vm_options == extra) return 0;
        if (vm_attempted && vm_home.empty()) {
            report(error, "JVM initialization previously failed; restart the process before retrying");
            return -1;
        }
        report(error, "The process already owns a JVM with a different configuration");
        return -1;
    }
    if (vm_attempted) {
        report(error, "JVM initialization previously failed; restart the process before retrying");
        return -1;
    }
    if (!std::ifstream(std::string(java_home) + "/lib/modules").good()) {
        report(error, "The bundled JVM module image is missing or unreadable");
        return -1;
    }
    // OpenJDK Mobile uses JAVA_HOME while resolving its statically linked module image.
    setenv("JAVA_HOME", java_home, 1);
    std::string home_option = std::string("-Djava.home=") + java_home;
    std::string path_option = std::string("-Djava.class.path=") + classpath;
    std::vector<JavaVMOption> options = {
        {home_option.data(), nullptr}, {path_option.data(), nullptr},
        {const_cast<char *>("-Xrs"), nullptr}, {const_cast<char *>("-Xms16m"), nullptr},
        {const_cast<char *>("-Xmx128m"), nullptr}, {const_cast<char *>("-Xss512k"), nullptr},
        {const_cast<char *>("-Djava.awt.headless=true"), nullptr},
        {const_cast<char *>("-Dfile.encoding=UTF-8"), nullptr},
    };
    for (auto &option : extra) options.push_back({option.data(), nullptr});
    JavaVMInitArgs arguments{};
    arguments.version = JNI_VERSION_1_8;
    arguments.nOptions = static_cast<jint>(options.size());
    arguments.options = options.data();
    arguments.ignoreUnrecognized = JNI_FALSE;
    JNIEnv *env = nullptr;
    vm_attempted = true;
    const jint status = JNI_CreateJavaVM(&vm, reinterpret_cast<void **>(&env), &arguments);
    if (status != JNI_OK || vm == nullptr || env == nullptr) {
        vm = nullptr;
        report(error, "JNI_CreateJavaVM failed: " + std::to_string(status));
        return -1;
    }
#if KTR_HAS_NATIVE_SHIMS
    if (ktr_native_install(env) != 0 || env->ExceptionCheck()) {
        report(error, exception_message(env));
        vm->DetachCurrentThread();
        return -1;
    }
#endif
    vm_home = java_home;
    vm_classpath = classpath;
    vm_options = extra;
    vm->DetachCurrentThread();
    return 0;
}

KTRSession *ktr_session_open(const uint16_t *config, size_t config_length,
                            const uint16_t *directory, size_t directory_length, char **error) {
    if (error != nullptr) *error = nullptr;
    if (!valid_text(config, config_length) || !valid_text(directory, directory_length)) {
        report(error, "Invalid session configuration text");
        return nullptr;
    }
    Environment thread;
    JNIEnv *env = thread.value;
    if (env == nullptr) {
        report(error, "Unable to attach to the Java VM");
        return nullptr;
    }
    jclass type = env->FindClass("org/skepsun/kototoro/source/host/EmbeddedSourceHost");
    if (type == nullptr) {
        report(error, exception_message(env));
        return nullptr;
    }
    jmethodID factory = env->GetStaticMethodID(type, "open",
        "(Ljava/lang/String;Ljava/lang/String;)Lorg/skepsun/kototoro/source/host/EmbeddedSourceHost;");
    jmethodID exchange = factory == nullptr ? nullptr :
        env->GetMethodID(type, "exchange", "(Ljava/lang/String;)Ljava/lang/String;");
    jmethodID close = exchange == nullptr ? nullptr : env->GetMethodID(type, "close", "()V");
    if (close == nullptr) {
        report(error, exception_message(env));
        return nullptr;
    }
    jstring configuration = java_string(env, config, config_length);
    jstring root = env->ExceptionCheck() ? nullptr : java_string(env, directory, directory_length);
    if (configuration == nullptr || root == nullptr || env->ExceptionCheck()) {
        report(error, exception_message(env));
        return nullptr;
    }
    jobject host = env->CallStaticObjectMethod(type, factory, configuration, root);
    if (host == nullptr || env->ExceptionCheck()) {
        report(error, exception_message(env));
        return nullptr;
    }
    jobject retained = env->NewGlobalRef(host);
    auto *session = static_cast<KTRSession *>(std::malloc(sizeof(KTRSession)));
    if (retained == nullptr || session == nullptr) {
        env->ExceptionClear();
        env->CallVoidMethod(host, close);
        env->ExceptionClear();
        if (retained != nullptr) env->DeleteGlobalRef(retained);
        std::free(session);
        report(error, "Unable to retain the source session");
        return nullptr;
    }
    *session = {retained, exchange, close};
    return session;
}

int ktr_session_exchange(KTRSession *session, const uint16_t *request, size_t request_length,
                         uint16_t **response, size_t *response_length, char **error) {
    if (error != nullptr) *error = nullptr;
    if (response != nullptr) *response = nullptr;
    if (response_length != nullptr) *response_length = 0;
    if (session == nullptr || response == nullptr || response_length == nullptr ||
        !valid_text(request, request_length)) {
        report(error, "Invalid exchange arguments");
        return -1;
    }
    Environment thread;
    JNIEnv *env = thread.value;
    if (env == nullptr) {
        report(error, "Unable to attach to the Java VM");
        return -1;
    }
    jstring input = java_string(env, request, request_length);
    if (input == nullptr || env->ExceptionCheck()) {
        report(error, exception_message(env));
        return -1;
    }
    auto output = static_cast<jstring>(env->CallObjectMethod(session->host, session->exchange, input));
    if (output == nullptr || env->ExceptionCheck()) {
        report(error, exception_message(env));
        return -1;
    }
    const jsize length = env->GetStringLength(output);
    auto *buffer = static_cast<uint16_t *>(std::malloc((static_cast<size_t>(length) + 1) * sizeof(uint16_t)));
    if (buffer == nullptr) {
        report(error, "Unable to allocate the source response");
        return -1;
    }
    env->GetStringRegion(output, 0, length, reinterpret_cast<jchar *>(buffer));
    if (env->ExceptionCheck()) {
        std::free(buffer);
        report(error, exception_message(env));
        return -1;
    }
    buffer[length] = 0;
    *response = buffer;
    *response_length = static_cast<size_t>(length);
    return 0;
}

int ktr_session_close(KTRSession *session, char **error) {
    if (error != nullptr) *error = nullptr;
    if (session == nullptr) return 0;
    Environment thread;
    JNIEnv *env = thread.value;
    if (env == nullptr) {
        report(error, "Unable to attach to the Java VM to close the source session");
        return -1;
    }
    env->CallVoidMethod(session->host, session->close);
    const bool failed = env->ExceptionCheck();
    if (failed) report(error, exception_message(env));
    env->DeleteGlobalRef(session->host);
    std::free(session);
    return failed ? -1 : 0;
}

namespace {
KTRWebKitHandler webkit_handler = nullptr;
jclass webkit_type = nullptr;
jmethodID webkit_event = nullptr;

std::vector<jchar> java_text(JNIEnv *env, jstring text) {
    std::vector<jchar> value;
    if (text == nullptr) return value;
    value.resize(static_cast<size_t>(env->GetStringLength(text)));
    if (!value.empty()) env->GetStringRegion(text, 0, static_cast<jsize>(value.size()), value.data());
    return value;
}

jstring JNICALL webkit_command(JNIEnv *env, jclass, jstring operation, jlong view, jstring payload) {
    if (webkit_handler == nullptr) return env->NewStringUTF("unavailable:WebKit is not connected");
    auto name = java_text(env, operation), body = java_text(env, payload);
    size_t length = 0;
    uint16_t *result = webkit_handler(reinterpret_cast<const uint16_t *>(name.data()), name.size(),
                                      static_cast<int64_t>(view), reinterpret_cast<const uint16_t *>(body.data()),
                                      body.size(), &length);
    if (result == nullptr) return nullptr;
    jstring text = env->NewString(reinterpret_cast<const jchar *>(result), static_cast<jsize>(length));
    std::free(result);
    return text;
}
}

int ktr_webkit_install(KTRWebKitHandler handler, char **error) {
    if (error != nullptr) *error = nullptr;
    if (handler == nullptr) {
        report(error, "A WebKit handler is required");
        return -1;
    }
    Environment thread;
    JNIEnv *env = thread.value;
    if (env == nullptr) {
        report(error, "Unable to attach to the Java VM");
        return -1;
    }
    jclass type = env->FindClass("org/skepsun/kototoro/ios/compat/IosWebKit");
    jmethodID event = type == nullptr ? nullptr : env->GetStaticMethodID(type, "onNativeEvent",
        "(JLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (event == nullptr) {
        report(error, exception_message(env));
        return -1;
    }
    JNINativeMethod method{const_cast<char *>("nativeCommand"),
        const_cast<char *>("(Ljava/lang/String;JLjava/lang/String;)Ljava/lang/String;"),
        reinterpret_cast<void *>(&webkit_command)};
    if (env->RegisterNatives(type, &method, 1) != JNI_OK) {
        report(error, exception_message(env));
        return -1;
    }
    webkit_type = static_cast<jclass>(env->NewGlobalRef(type));
    webkit_event = event;
    webkit_handler = handler;
    // Tells the platform adapter that WebView calls have a native backend (IosWebKit.isAvailable).
    jclass system = env->FindClass("java/lang/System");
    jmethodID set = system == nullptr ? nullptr : env->GetStaticMethodID(system, "setProperty",
        "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    if (set != nullptr) {
        env->CallStaticObjectMethod(system, set, env->NewStringUTF("kototoro.webkit"), env->NewStringUTF("native"));
    }
    if (env->ExceptionCheck()) {
        report(error, exception_message(env));
        return -1;
    }
    return 0;
}

int ktr_webkit_dispatch(int64_t view, const uint16_t *event, size_t event_length,
                        const uint16_t *payload, size_t payload_length,
                        uint16_t **result, size_t *result_length, char **error) {
    if (error != nullptr) *error = nullptr;
    if (result != nullptr) *result = nullptr;
    if (result_length != nullptr) *result_length = 0;
    if (webkit_type == nullptr || !valid_text(event, event_length) || !valid_text(payload, payload_length)) {
        report(error, "WebKit bridge is not installed");
        return -1;
    }
    Environment thread;
    JNIEnv *env = thread.value;
    if (env == nullptr) {
        report(error, "Unable to attach to the Java VM");
        return -1;
    }
    jstring name = java_string(env, event, event_length);
    jstring body = name == nullptr ? nullptr : java_string(env, payload, payload_length);
    if (body == nullptr || env->ExceptionCheck()) {
        report(error, exception_message(env));
        return -1;
    }
    auto output = static_cast<jstring>(env->CallStaticObjectMethod(webkit_type, webkit_event,
                                                                    static_cast<jlong>(view), name, body));
    if (env->ExceptionCheck()) {
        report(error, exception_message(env));
        return -1;
    }
    if (output == nullptr || result == nullptr || result_length == nullptr) return 0;
    const jsize length = env->GetStringLength(output);
    auto *buffer = static_cast<uint16_t *>(std::malloc((static_cast<size_t>(length) + 1) * sizeof(uint16_t)));
    if (buffer == nullptr) {
        report(error, "Unable to allocate the WebKit event result");
        return -1;
    }
    env->GetStringRegion(output, 0, length, reinterpret_cast<jchar *>(buffer));
    buffer[length] = 0;
    *result = buffer;
    *result_length = static_cast<size_t>(length);
    return 0;
}

#else
static void unavailable(char **error) {
    if (error != nullptr) *error = strdup("Embedded JVM is not included in this build");
}
int ktr_vm_available(void) { return 0; }
int ktr_vm_start(const char *, const char *, const char *const *, size_t, char **error) {
    unavailable(error);
    return -1;
}
KTRSession *ktr_session_open(const uint16_t *, size_t, const uint16_t *, size_t, char **error) {
    unavailable(error);
    return nullptr;
}
int ktr_session_exchange(KTRSession *, const uint16_t *, size_t,
                         uint16_t **response, size_t *length, char **error) {
    if (response != nullptr) *response = nullptr;
    if (length != nullptr) *length = 0;
    unavailable(error);
    return -1;
}
int ktr_session_close(KTRSession *, char **error) {
    if (error != nullptr) *error = nullptr;
    return 0;
}
#endif
#if !KTR_HAS_JVM
int ktr_webkit_install(KTRWebKitHandler, char **error) {
    unavailable(error);
    return -1;
}
int ktr_webkit_dispatch(int64_t, const uint16_t *, size_t, const uint16_t *, size_t,
                        uint16_t **result, size_t *result_length, char **error) {
    if (result != nullptr) *result = nullptr;
    if (result_length != nullptr) *result_length = 0;
    unavailable(error);
    return -1;
}
#endif
void ktr_free(void *memory) { std::free(memory); }
void *ktr_malloc(size_t size) { return std::malloc(size); }
