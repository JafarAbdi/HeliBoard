#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

struct transcribe_model;
struct transcribe_session;

namespace pixel_voice {

class Engine final {
public:
    Engine();
    ~Engine();

    Engine(const Engine &) = delete;
    Engine & operator=(const Engine &) = delete;

    void load(const std::string & model_path);
    void start();
    std::string feed(const int16_t * pcm, size_t sample_count);
    std::string finish();
    void cancel() noexcept;
    void reset() noexcept;

private:
    static bool abort_requested(void * user_data) noexcept;
    std::string copy_stream_text(bool final_text) const;
    void check_status(int status, const char * operation) const;
    void release() noexcept;

    transcribe_model * model_ = nullptr;
    transcribe_session * session_ = nullptr;
    std::atomic<bool> cancelled_{false};
    bool active_ = false;
    std::vector<float> float_pcm_;
};

}
