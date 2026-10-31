#pragma once
#include <cstdint>

// The settings the render side reads (native/vulkan.cpp on the render worker, src/d3d/render_thread.cpp, and the FPS
// counter it draws, src/common/menu.cpp), as one copy published by the game thread at each present: the settings rows
// (src/common/settings_rows.cpp) change Options and app::Settings on the game thread while the worker still draws the
// frame before, so the worker never reads those plain fields itself. render_settings.cpp
namespace game {

struct RenderSettings {
    // app::Settings
    uint32_t render_percent = 100;
    uint32_t aniso = 0;
    bool vsync = true;
    // Options
    uint32_t screen_filter = 0;
    uint32_t anti_aliasing = 0;
    uint32_t reflections = 0;
    bool road_reflections = true;
    bool bloom = true;
    bool shadows = true;
    bool fog = true;
    bool shader_preload = false;
    bool fps_counter = false;
    uint32_t fps_position = 0;
    bool fps_usage = true;
    bool fps_temps = true;
    // game::Aspect(), the frame's shape as the presenter letterboxes it
    float aspect = 16.0f / 9.0f;
};

// game thread: the settings as they are now become the render side's; at boot after the settings load and at each
// present before the frame is handed over (D3DDevice_Swap), so a frame is drawn with one coherent set
void PublishRenderSettings();
// any thread: a copy of the last published set (under a mutex)
RenderSettings LatestRenderSettings();

// the render worker (src/d3d/render_thread.cpp): the set published at the present it is about to draw, carried in that
// present's command, so the worker's frames follow the game's in order however far behind it runs
void HandOverRenderSettings(const RenderSettings& r);
// the render side, once a frame (native/vulkan.cpp's Present): the set handed over last on the worker, or with no
// worker (the backend called on the game thread) the latest published
RenderSettings FrameRenderSettings();
// app::RenderScale's: the render targets' scale for a guest height at r's render resolution and anti-aliasing
float RenderScaleOf(const RenderSettings& r, uint32_t guest_height);

}  // namespace game
