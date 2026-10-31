#pragma once
// What the Android host's parts share: the window, the app's lifecycle, the event routing and the launcher shell
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

union SDL_Event;
struct SDL_Window;

namespace input {
struct Pad;
}

namespace host {

SDL_Window* Window();
// the app is in front with a window to draw to; the renderer and the game wait while it is not
bool Foreground();
// counts the native windows Android gave the app: the renderer makes its surface again when this changes
uint32_t SurfaceEpoch();
// the game thread, before a present: returns once the app is in front again
void WaitForeground();
// the window's size in pixels
void WindowPixels(int& w, int& h);

// the game folder the launcher picked (GameActivity.gameDir: Settings.gameDir, the app's files/game by default);
// empty off Android or when the activity has none
std::filesystem::path LauncherGameDir();
// the game's rumble on the phone (GameActivity.rumble), for port 0 with no controller; 0 to 65535 per motor
void PhoneRumble(uint16_t left, uint16_t right);
// the in-game settings' CONTROLS rows, which are the overlay's (GameActivity.settingsRow): row 0 mode, 1 steering,
// 2 tilt sensitivity, 3 opacity, 4 size, 5 the controls' editor, 6 the HUD editor. step != 0 changes the value (opens
// the editor). The value as shown; empty when the shell did not answer in time (a change handed to it may still be
// made: no one else makes it); nullopt with no shell (off Android). Game thread
std::optional<std::string> ShellRow(int row, int step);
// the performance overlay's numbers, percent and degrees, -1 where the device does not tell (native/stats.cpp)
void SamplePerf();
float CpuLoad();
float GpuLoad();
float CpuTemp();
float GpuTemp();
float BatteryTemp();

}  // namespace host

// controllers through SDL (input.cpp): the main thread hands over their plug events
namespace input {
void OnPadEvent(const SDL_Event& e);
}

// SDL's keyboard, mouse and the touches the overlay leaves to the game: FEng's menus by touch (touch.cpp)
namespace touch {
// main thread, every input event; true when it was taken
bool OnEvent(const SDL_Event& e);
// port 0's pad before the game reads it: the menus' taps and swipes, the back key
void Apply(input::Pad& pad);
// any thread: the FEng menu's button prompts, each its icon (ButtonArt.Icon's name by the string's hash, PromptIcon;
// "" when unknown), its label ("ACCEPT"; the triggers' ZOOM OUT / ZOOM IN where they zoom) and the pad button it is
// for (game::FePrompts' index: 0 A, 1 B, 2 X, 3 Y, 4 LT, 5 RT); none with no menu up (the touches the overlay leaves
// are the menu's pointer and swipes only then)
struct MenuPrompt {
    std::string icon;
    std::string label;
    int pad;
};
std::optional<std::vector<MenuPrompt>> CurrentMenuPrompts();
// a prompt string's language hash to its icon's name, "" for one not known (prompt_icons.cpp)
const char* PromptIcon(uint32_t hash);
}  // namespace touch

// ImGui's frame (ui.cpp): no input, the FPS counter alone draws in it
namespace ui {
// render thread, before ImGui::NewFrame: the display size and the frame time
void NewFrame(float width, float height);
// render thread, under app::UiLock, before the backend's NewFrame: the game's font into the atlas once it is loaded;
// true when the atlas is to be uploaded again
bool ApplyGameFont();
}  // namespace ui
