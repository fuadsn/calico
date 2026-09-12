cmake_minimum_required(VERSION 3.22)
project(calico_coach LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# llama.cpp is prebuilt with the Hexagon NPU backend by tools/build-llama-snapdragon.py.
# The NPU skels need Qualcomm's hexagon-clang, which the Android NDK does not ship.
set(PREBUILT "${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI}")
set(LLAMA_INCLUDE "${CMAKE_CURRENT_LIST_DIR}/../../../../build/llama-snapdragon/include")
if(NOT EXISTS "${PREBUILT}/libllama.so")
    message(FATAL_ERROR "Prebuilt llama.cpp NPU libraries are missing. Run: python tools/build-llama-snapdragon.py")
endif()
foreach(library llama ggml ggml-base)
    add_library(${library} SHARED IMPORTED)
    set_target_properties(${library} PROPERTIES
        IMPORTED_LOCATION "${PREBUILT}/lib${library}.so"
        INTERFACE_INCLUDE_DIRECTORIES "${LLAMA_INCLUDE}")
endforeach()
add_library(calico_coach SHARED coach_jni.cpp)
target_link_libraries(calico_coach PRIVATE llama ggml ggml-base android log)
# NDK 29 also makes the dependency objects compatible with 16 KB Android pages.
target_link_options(calico_coach PRIVATE "-Wl,-z,max-page-size=16384")
