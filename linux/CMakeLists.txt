# Linux x86-64 host for benchmarking: the same generated code, SDK runtime, src/ and native/ (Vulkan backend, shader
# compiler, keyboard/menu taps, ui, audio, input) as the Android library, with linux/ standing in for the Android-only
# parts (main.cpp, game_files.cpp, stats.cpp, activity.cpp). The Android build (../CMakeLists.txt) is not touched.
#   cmake -S linux -B linux/build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_COMPILER=clang++ -DCMAKE_C_COMPILER=clang \
#         -DXBE_IMAGE=<default.xbe>
cmake_minimum_required(VERSION 3.22)
project(mostwanted_linux LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(REPO ${CMAKE_CURRENT_SOURCE_DIR}/.. ABSOLUTE)
set(NATIVE ${REPO}/native)
set(GAME ${REPO}/src)
set(LINUX ${CMAKE_CURRENT_SOURCE_DIR})
set(SDK ${REPO}/sdk)

set(XBE_GENERATED ${REPO}/generated/game CACHE PATH "the recompiled code of default.xbe")
set(XBE_IMAGE "" CACHE FILEPATH "the xbe it was recompiled from")
set(GEN ${XBE_GENERATED})
if(NOT EXISTS ${GEN}/x86_func_mapping.cpp OR NOT EXISTS ${XBE_IMAGE})
    message(FATAL_ERROR "need XBE_GENERATED (xbe_recomp output) and XBE_IMAGE (default.xbe)")
endif()
file(STRINGS ${REPO}/config/game.toml GAME_PARTITIONS REGEX "^partitions *=")
string(REGEX MATCH "[0-9]+" GAME_PARTITIONS "${GAME_PARTITIONS}")
math(EXPR last "${GAME_PARTITIONS} - 1")
set(GEN_SOURCES ${GEN}/x86_func_mapping.cpp)
foreach(i RANGE ${last})
    list(APPEND GEN_SOURCES ${GEN}/x86_recomp.${i}.cpp)
endforeach()
file(SHA1 ${XBE_IMAGE} xbe_sha1)
file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp CONTENT "extern const char kImageSha1[] = \"${xbe_sha1}\";\n")
file(CONFIGURE OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/build_info.h CONTENT "#define BUILD_COMMIT \"linux-bench\"\n")

find_package(SDL3 REQUIRED CONFIG)
find_package(Vulkan REQUIRED)
find_package(glslang REQUIRED CONFIG)
include(FetchContent)
FetchContent_Declare(tomlplusplus GIT_REPOSITORY https://github.com/marzer/tomlplusplus.git GIT_TAG v3.4.0 GIT_SHALLOW TRUE)
FetchContent_Declare(imgui GIT_REPOSITORY https://github.com/ocornut/imgui.git GIT_TAG v1.91.0 GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(tomlplusplus imgui)

file(GLOB RUNTIME_SOURCES ${SDK}/runtime/*/*.cpp)
list(FILTER RUNTIME_SOURCES EXCLUDE REGEX
     "/runtime/app/|/core/input\\.cpp$|/gpu/(d3d12|render_thread)\\.cpp$|/audio/xaudio2\\.cpp$|/xdk/(dsound|d3d8)\\.cpp$")
file(GLOB GAME_HOST_SOURCES ${GAME}/*/*.cpp)
# native/ minus the parts that need the JVM or Android's storage; linux/ has their stand ins
file(GLOB ANDROID_SOURCES ${NATIVE}/*.cpp)
list(FILTER ANDROID_SOURCES EXCLUDE REGEX "/native/(main|game_files|stats|activity|vulkan)\\.cpp$")
file(GLOB LINUX_SOURCES ${LINUX}/*.cpp)
set(IMGUI_SOURCES ${imgui_SOURCE_DIR}/imgui.cpp ${imgui_SOURCE_DIR}/imgui_draw.cpp ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp ${imgui_SOURCE_DIR}/backends/imgui_impl_vulkan.cpp)

add_executable(mostwanted ${RUNTIME_SOURCES} ${GAME_HOST_SOURCES} ${ANDROID_SOURCES} ${LINUX_SOURCES} ${GEN_SOURCES}
               ${IMGUI_SOURCES} ${CMAKE_CURRENT_BINARY_DIR}/image_sha1.cpp)
# linux/compat: arm_neon.h through SIMDe (src/d3d/render_thread.cpp)
target_include_directories(mostwanted PRIVATE ${LINUX}/compat ${SDK}/harness/compat ${NATIVE} ${GAME} ${SDK}/runtime
                           ${GEN} ${CMAKE_CURRENT_BINARY_DIR} /usr/include/glslang)
target_include_directories(mostwanted SYSTEM PRIVATE ${imgui_SOURCE_DIR})
target_compile_options(mostwanted PRIVATE -fno-strict-aliasing -fsigned-char -g -march=x86-64-v2)
set(HOST_SOURCES ${RUNTIME_SOURCES} ${GAME_HOST_SOURCES} ${ANDROID_SOURCES} ${LINUX_SOURCES})
set_source_files_properties(${HOST_SOURCES} PROPERTIES COMPILE_OPTIONS "-include;${NATIVE}/compat/crt.h")
# as the Android build: -O2 for the recompiled code
set(MW_GEN_OPT "-O2" CACHE STRING "optimisation of the recompiled code")
set_source_files_properties(${GEN_SOURCES} PROPERTIES COMPILE_OPTIONS "${MW_GEN_OPT};-g0;-w;-fno-strict-aliasing;-fsigned-char")
set_source_files_properties(${NATIVE}/vk_shader.cpp PROPERTIES COMPILE_DEFINITIONS ENABLE_HLSL)
target_link_libraries(mostwanted PRIVATE SDL3::SDL3 Vulkan::Vulkan tomlplusplus::tomlplusplus glslang::glslang
                      glslang::SPIRV glslang::glslang-default-resource-limits pthread)
# A/B switches of the SDK (compile time): -DMW_RENDER_THREAD=0 / -DMW_RAW_VERTICES=0
set(MW_RENDER_THREAD "1" CACHE STRING "XBE_RENDER_THREAD")
set(MW_RAW_VERTICES "1" CACHE STRING "XBE_RAW_VERTICES")
target_compile_definitions(mostwanted PRIVATE XBE_RENDER_THREAD=${MW_RENDER_THREAD} XBE_RAW_VERTICES=${MW_RAW_VERTICES})
