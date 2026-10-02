cmake_minimum_required(VERSION 3.22.1)
project(pixel_voice_native LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(TRANSCRIBE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_INSTALL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_METAL OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_VULKAN OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_CUDA OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_HIP OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_OPENMP OFF CACHE BOOL "" FORCE)
set(TRANSCRIBE_USE_SYSTEM_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

if(ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

if(ANDROID)
    add_link_options("LINKER:-z,max-page-size=16384" "LINKER:-z,common-page-size=16384")
endif()

add_subdirectory(third_party/transcribe.cpp transcribe-build)
target_include_directories(transcribe PUBLIC third_party/transcribe.cpp/include)

add_library(pixel_voice_engine STATIC
    dictation/src/main/cpp/engine.cpp
)
target_include_directories(pixel_voice_engine
    PUBLIC dictation/src/main/cpp
    PRIVATE third_party/transcribe.cpp/include
)
target_link_libraries(pixel_voice_engine PUBLIC transcribe)

if(ANDROID)
    add_library(pixelvoice_jni SHARED dictation/src/main/cpp/native_bridge.cpp)
    target_include_directories(pixelvoice_jni PRIVATE third_party/transcribe.cpp/include)
    target_link_libraries(pixelvoice_jni PRIVATE pixel_voice_engine log)
else()
    add_executable(pixel_voice_smoke
        host/smoke.cpp
        third_party/transcribe.cpp/examples/common/wav.cpp
    )
    target_include_directories(pixel_voice_smoke PRIVATE
        third_party/transcribe.cpp/examples/common
        third_party/transcribe.cpp/examples/cli
    )
    target_link_libraries(pixel_voice_smoke PRIVATE pixel_voice_engine)
endif()
