#pragma once
#include <atomic>
#include <cstddef>

// The HUD's speedometer, minimap, race information (top right) and EA Trax banner: moved and shown or hidden as the HUD
// editor (HudEditorActivity) and the HUD category leave them in the settings file (Options' hud_*, game_info.cpp);
// src/game/hud_layout.cpp applies them to their FEng groups each frame
namespace game {

enum HudItem : size_t { kHudSpeedometer, kHudMinimap, kHudRaceInfo, kHudTrax, kHudItems };

// the settings file's names, "hud_<id>_x", "_y" and "_shown" (Settings.java's HUD_IDS)
inline constexpr const char* kHudIds[kHudItems] = {"speedometer", "minimap", "race_info", "ea_trax"};

// any thread (GameActivity.nativeReloadHud, the game back in front after the HUD editor): the game thread reads the
// HUD's keys from the settings file again at its next frame
inline std::atomic<bool> g_hud_reload{false};
void ReloadHudIfAsked();  // src/common/game_info.cpp: game thread

void ApplyHudLayout();  // src/game/hud_layout.cpp: game thread, after each FEngine::Update

}  // namespace game
