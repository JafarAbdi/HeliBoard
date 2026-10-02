#include "engine.h"

#include "transcribe.h"

#include <algorithm>
#include <limits>
#include <stdexcept>
#include <string>

namespace pixel_voice {
namespace {

std::string status_message(int status, const char * operation) {
    const char * detail = transcribe_status_string(status);
    return std::string(operation) + " failed: " + (detail == nullptr ? "unknown status" : detail);
}

std::string copy_bytes(const char * text, uint64_t size, const char * field) {
    if (size > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        throw std::runtime_error(std::string(field) + " is too large");
    }
    if (size != 0 && text == nullptr) {
        throw std::runtime_error(std::string(field) + " returned a null pointer");
    }
    return size == 0 ? std::string{} : std::string(text, static_cast<size_t>(size));
}

}

Engine::Engine() = default;

Engine::~Engine() {
    release();
}

void Engine::load(const std::string & model_path) {
    if (model_path.empty()) {
        throw std::invalid_argument("model path is empty");
    }
    release();

    transcribe_model_load_params load_params;
    transcribe_model_load_params_init(&load_params);
    load_params.backend = TRANSCRIBE_BACKEND_CPU;
    transcribe_status status = transcribe_model_load_file(model_path.c_str(), &load_params, &model_);
    if (status != TRANSCRIBE_OK) {
        model_ = nullptr;
        throw std::runtime_error(status_message(status, "model load"));
    }

    try {
        transcribe_capabilities capabilities;
        transcribe_capabilities_init(&capabilities);
        check_status(transcribe_model_get_capabilities(model_, &capabilities), "capability query");
        if (capabilities.native_sample_rate != 16000) {
            throw std::runtime_error("model does not accept 16 kHz audio");
        }
        if (!capabilities.supports_streaming) {
            throw std::runtime_error("model does not support streaming");
        }
        if (!transcribe_model_supports(model_, TRANSCRIBE_FEATURE_CANCELLATION)) {
            throw std::runtime_error("model does not support cancellation");
        }
        if (std::string(transcribe_model_arch_string(model_)) != "parakeet") {
            throw std::runtime_error("model architecture is not Parakeet");
        }

        transcribe_session_params session_params;
        transcribe_session_params_init(&session_params);
        status = transcribe_session_init(model_, &session_params, &session_);
        check_status(status, "session creation");
        transcribe_set_abort_callback(session_, &Engine::abort_requested, &cancelled_);
    } catch (...) {
        release();
        throw;
    }
}

void Engine::start() {
    if (session_ == nullptr) {
        throw std::logic_error("model is not loaded");
    }
    if (active_) {
        throw std::logic_error("recording is already active");
    }
    cancelled_.store(false, std::memory_order_release);

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.language = "en";
    run_params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    transcribe_stream_params stream_params;
    transcribe_stream_params_init(&stream_params);
    check_status(transcribe_stream_begin(session_, &run_params, &stream_params), "stream start");
    active_ = true;
}

std::string Engine::feed(const int16_t * pcm, size_t sample_count) {
    if (!active_) {
        throw std::logic_error("recording is not active");
    }
    if (pcm == nullptr || sample_count == 0) {
        throw std::invalid_argument("audio block is empty");
    }
    if (sample_count > static_cast<size_t>(std::numeric_limits<int>::max())) {
        throw std::invalid_argument("audio block is too large");
    }

    float_pcm_.resize(sample_count);
    std::transform(pcm, pcm + sample_count, float_pcm_.begin(), [](int16_t sample) {
        return static_cast<float>(sample) / 32768.0f;
    });

    transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    const transcribe_status status =
        transcribe_stream_feed(session_, float_pcm_.data(), static_cast<int>(sample_count), &update);
    if (status != TRANSCRIBE_OK) {
        active_ = false;
    }
    check_status(status, "stream feed");
    return copy_stream_text(false);
}

std::string Engine::finish() {
    if (!active_) {
        throw std::logic_error("recording is not active");
    }
    transcribe_stream_update update;
    transcribe_stream_update_init(&update);
    const transcribe_status status = transcribe_stream_finalize(session_, &update);
    active_ = false;
    check_status(status, "stream finish");
    if (transcribe_was_truncated(session_)) {
        throw std::runtime_error("stream finish failed: transcript was truncated");
    }
    return copy_stream_text(true);
}

void Engine::cancel() noexcept {
    cancelled_.store(true, std::memory_order_release);
}

void Engine::reset() noexcept {
    if (session_ != nullptr) {
        transcribe_stream_reset(session_);
    }
    active_ = false;
    cancelled_.store(false, std::memory_order_release);
}

bool Engine::abort_requested(void * user_data) noexcept {
    if (user_data == nullptr) {
        return true;
    }
    return static_cast<std::atomic<bool> *>(user_data)->load(std::memory_order_acquire);
}

std::string Engine::copy_stream_text(bool final_text) const {
    transcribe_stream_text text;
    transcribe_stream_text_init(&text);
    check_status(transcribe_stream_get_text(session_, &text), "stream text query");
    if (final_text) {
        return copy_bytes(text.full_text, text.full_text_bytes, "full transcript");
    }
    std::string result = copy_bytes(text.committed_text, text.committed_text_bytes, "committed transcript");
    result += copy_bytes(text.tentative_text, text.tentative_text_bytes, "tentative transcript");
    return result;
}

void Engine::check_status(int status, const char * operation) const {
    if (status != TRANSCRIBE_OK) {
        throw std::runtime_error(status_message(status, operation));
    }
}

void Engine::release() noexcept {
    if (session_ != nullptr) {
        transcribe_session_free(session_);
        session_ = nullptr;
    }
    if (model_ != nullptr) {
        transcribe_model_free(model_);
        model_ = nullptr;
    }
    active_ = false;
    cancelled_.store(false, std::memory_order_release);
}

}
