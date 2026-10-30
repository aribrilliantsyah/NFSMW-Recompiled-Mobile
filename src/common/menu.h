#pragma once
#include <cstdint>

// what draws over the game: the FPS counter alone (GameInfo::ui); every setting is the launcher's (Settings.java)
namespace app {

void DrawUi(double fps);
inline constexpr uint32_t kFpsCapRefresh = 1;  // Settings::fps_cap: just under the display's refresh rate
void PaceFrame();  // app::Pace, with kFpsCapRefresh; game thread, after the present

}  // namespace app
