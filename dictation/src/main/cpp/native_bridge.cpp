#include "engine.h"

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

struct NativeHandle {
    std::mutex mutex;
    std::shared_ptr<pixel_voice::Engine> engine = std::make_shared<pixel_voice::Engine>();
};

std::mutex registry_mutex;
std::unordered_map<jlong, std::shared_ptr<NativeHandle>> registry;
std::atomic<jlong> next_handle{1};

std::shared_ptr<NativeHandle> handle_from(jlong value) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto entry = registry.find(value);
    if (value == 0 || entry == registry.end()) {
        throw std::invalid_argument("native engine is closed");
    }
    return entry->second;
}

std::shared_ptr<pixel_voice::Engine> engine_from(jlong value) {
    const std::shared_ptr<NativeHandle> handle = handle_from(value);
    std::lock_guard<std::mutex> lock(handle->mutex);
    if (!handle->engine) {
        throw std::invalid_argument("native engine is closed");
    }
    return handle->engine;
}

void append_utf8(std::string & output, uint32_t codepoint) {
    if (codepoint <= 0x7f) {
        output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
        output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
        output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
        output.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
        output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
}

std::string java_string_to_utf8(JNIEnv * env, jstring input) {
    if (input == nullptr) {
        throw std::invalid_argument("string is null");
    }
    const jsize length = env->GetStringLength(input);
    const jchar * chars = env->GetStringChars(input, nullptr);
    if (chars == nullptr) {
        throw std::runtime_error("could not read Java string");
    }
    std::string result;
    try {
        result.reserve(static_cast<size_t>(length) * 3);
        for (jsize index = 0; index < length; ++index) {
            uint32_t codepoint = chars[index];
            if (codepoint >= 0xd800 && codepoint <= 0xdbff && index + 1 < length) {
                const uint32_t low = chars[index + 1];
                if (low >= 0xdc00 && low <= 0xdfff) {
                    codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (low - 0xdc00);
                    ++index;
                }
            }
            if (codepoint >= 0xd800 && codepoint <= 0xdfff) {
                codepoint = 0xfffd;
            }
            append_utf8(result, codepoint);
        }
    } catch (...) {
        env->ReleaseStringChars(input, chars);
        throw;
    }
    env->ReleaseStringChars(input, chars);
    return result;
}

jstring utf8_to_java_string(JNIEnv * env, const std::string & input) {
    std::vector<jchar> output;
    output.reserve(input.size());
    size_t index = 0;
    while (index < input.size()) {
        const uint8_t first = static_cast<uint8_t>(input[index++]);
        uint32_t codepoint = 0xfffd;
        int continuation_count = 0;
        if (first < 0x80) {
            codepoint = first;
        } else if ((first & 0xe0) == 0xc0) {
            codepoint = first & 0x1f;
            continuation_count = 1;
        } else if ((first & 0xf0) == 0xe0) {
            codepoint = first & 0x0f;
            continuation_count = 2;
        } else if ((first & 0xf8) == 0xf0) {
            codepoint = first & 0x07;
            continuation_count = 3;
        }

        bool valid = continuation_count != 0 || first < 0x80;
        for (int count = 0; valid && count < continuation_count; ++count) {
            if (index >= input.size()) {
                valid = false;
                break;
            }
            const uint8_t next = static_cast<uint8_t>(input[index]);
            if ((next & 0xc0) != 0x80) {
                valid = false;
                break;
            }
            ++index;
            codepoint = (codepoint << 6) | (next & 0x3f);
        }
        if (!valid || codepoint > 0x10ffff || (codepoint >= 0xd800 && codepoint <= 0xdfff) ||
            (continuation_count == 1 && codepoint < 0x80) ||
            (continuation_count == 2 && codepoint < 0x800) ||
            (continuation_count == 3 && codepoint < 0x10000)) {
            codepoint = 0xfffd;
        }
        if (codepoint <= 0xffff) {
            output.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            output.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            output.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
    }
    return env->NewString(output.data(), static_cast<jsize>(output.size()));
}

void throw_java(JNIEnv * env, const char * message) noexcept {
    if (env->ExceptionCheck()) {
        return;
    }
    jclass type = env->FindClass("java/lang/RuntimeException");
    if (type != nullptr) {
        env->ThrowNew(type, message == nullptr ? "native error" : message);
        env->DeleteLocalRef(type);
    }
}

template <typename Function, typename Result>
Result guarded(JNIEnv * env, Result failure, Function function) noexcept {
    try {
        return function();
    } catch (const std::exception & error) {
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "unknown native error");
    }
    return failure;
}

template <typename Function>
void guarded_void(JNIEnv * env, Function function) noexcept {
    try {
        function();
    } catch (const std::exception & error) {
        throw_java(env, error.what());
    } catch (...) {
        throw_java(env, "unknown native error");
    }
}

}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeCreate(JNIEnv * env, jclass) noexcept {
    return guarded(env, static_cast<jlong>(0), [] {
        const jlong id = next_handle.fetch_add(1, std::memory_order_relaxed);
        std::lock_guard<std::mutex> lock(registry_mutex);
        registry.emplace(id, std::make_shared<NativeHandle>());
        return id;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeLoad(JNIEnv * env, jclass, jlong handle, jstring path) noexcept {
    guarded_void(env, [&] { engine_from(handle)->load(java_string_to_utf8(env, path)); });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeStart(JNIEnv * env, jclass, jlong handle) noexcept {
    guarded_void(env, [&] { engine_from(handle)->start(); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeFeed(JNIEnv * env, jclass, jlong handle, jshortArray pcm) noexcept {
    return guarded(env, static_cast<jstring>(nullptr), [&] {
        if (pcm == nullptr) {
            throw std::invalid_argument("audio block is null");
        }
        const jsize size = env->GetArrayLength(pcm);
        if (size <= 0) {
            throw std::invalid_argument("audio block is empty");
        }
        std::vector<int16_t> samples(static_cast<size_t>(size));
        env->GetShortArrayRegion(pcm, 0, size, reinterpret_cast<jshort *>(samples.data()));
        if (env->ExceptionCheck()) {
            throw std::runtime_error("could not read audio block");
        }
        return utf8_to_java_string(env, engine_from(handle)->feed(samples.data(), samples.size()));
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeFinish(JNIEnv * env, jclass, jlong handle) noexcept {
    return guarded(env, static_cast<jstring>(nullptr), [&] {
        return utf8_to_java_string(env, engine_from(handle)->finish());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeCancel(JNIEnv * env, jclass, jlong handle) noexcept {
    guarded_void(env, [&] { engine_from(handle)->cancel(); });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeReset(JNIEnv * env, jclass, jlong handle) noexcept {
    guarded_void(env, [&] { engine_from(handle)->reset(); });
}

extern "C" JNIEXPORT void JNICALL
Java_dev_juruc_pixelvoice_NativeEngine_nativeDestroy(JNIEnv * env, jclass, jlong value) noexcept {
    guarded_void(env, [&] {
        std::shared_ptr<NativeHandle> handle;
        {
            std::lock_guard<std::mutex> lock(registry_mutex);
            const auto entry = registry.find(value);
            if (value == 0 || entry == registry.end()) {
                return;
            }
            handle = entry->second;
            registry.erase(entry);
        }
        std::lock_guard<std::mutex> lock(handle->mutex);
        handle->engine.reset();
    });
}
