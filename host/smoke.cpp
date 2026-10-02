#include "engine.h"
#include "wav.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <exception>
#include <future>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr size_t kBlockSamples = 1600;
const char * const kExpectedTranscript =
    "And so, my fellow Americans, ask not what your country can do for you. Ask what you can do for your country";

std::vector<int16_t> to_pcm16(const std::vector<float> & source) {
    std::vector<int16_t> result(source.size());
    std::transform(source.begin(), source.end(), result.begin(), [](float sample) {
        const float scaled = std::clamp(sample, -1.0f, 32767.0f / 32768.0f) * 32768.0f;
        return static_cast<int16_t>(scaled);
    });
    return result;
}

std::string drain_and_finish(pixel_voice::Engine & engine, const std::vector<int16_t> & pcm) {
    for (size_t offset = 0; offset < pcm.size(); offset += kBlockSamples) {
        const size_t count = std::min(kBlockSamples, pcm.size() - offset);
        engine.feed(pcm.data() + offset, count);
    }
    return engine.finish();
}

}

int main(int argc, char ** argv) {
    if (argc != 3) {
        std::cerr << "usage: pixel_voice_smoke MODEL.gguf jfk.wav\n";
        return 2;
    }

    try {
        std::vector<float> wav;
        std::string wav_error;
        if (!transcribe_cli::load_wav_mono_16k(argv[2], wav, wav_error)) {
            throw std::runtime_error(wav_error);
        }
        const std::vector<int16_t> pcm = to_pcm16(wav);

        pixel_voice::Engine engine;
        engine.load(argv[1]);

        engine.start();
        const std::string empty_transcript = engine.finish();
        if (!empty_transcript.empty()) {
            throw std::runtime_error("zero-audio finalize returned text: " + empty_transcript);
        }

        engine.start();
        std::promise<void> feed_started;
        std::future<void> feed_gate = feed_started.get_future();
        std::future<std::string> cancelled_feed = std::async(std::launch::async, [&] {
            feed_started.set_value();
            try {
                return engine.feed(pcm.data(), pcm.size());
            } catch (const std::exception & error) {
                return std::string("ERROR: ") + error.what();
            }
        });
        feed_gate.get();
        engine.cancel();
        const std::string cancel_result = cancelled_feed.get();
        if (cancel_result.find("aborted") == std::string::npos) {
            throw std::runtime_error("concurrent cancel request did not abort feed: " + cancel_result);
        }

        engine.reset();
        engine.start();
        const std::string transcript = drain_and_finish(engine, pcm);
        if (transcript != kExpectedTranscript) {
            throw std::runtime_error("unexpected transcript\nexpected: " + std::string(kExpectedTranscript) +
                                     "\nactual:   " + transcript);
        }

        std::cout << "PASS transcript: " << transcript << '\n';
        std::cout << "PASS zero-audio finalize\n";
        std::cout << "PASS concurrent cancel request, reset, and native full-audio finalize\n";
        return 0;
    } catch (const std::exception & error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    } catch (...) {
        std::cerr << "FAIL unknown exception\n";
        return 1;
    }
}
