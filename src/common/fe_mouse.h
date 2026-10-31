#pragma once
#include <string>

// Most Wanted's FEng mouse, the touch screen (src/game/fe_mouse.cpp)
namespace game {

void FeFrame();             // game thread, at each present
bool FeMenuShown();         // any thread: a menu that takes the pointer is up
bool FeMouseDown();         // game thread: FEng's mouse button down this update
bool FeFinger(float& x, float& y);  // any thread: the finger in FEng's screen, true while it is down
// the menu's button prompts lately, for A, B, X, Y and the triggers (L and R, the showcase's zoom): shown, and its
// label in capitals ("DEFAULTS") with the string's language hash (FEString::LabelHash, the same in every language;
// 0 when the string has none); and whether it prompts the right stick (the showcase's ROTATE, the map's cursor)
constexpr int kFePromptLT = 4, kFePromptRT = 5, kFePromptCount = 6;
struct FePrompts {
    bool shown[kFePromptCount] = {};
    std::string label[kFePromptCount];
    uint32_t hash[kFePromptCount] = {};
    bool right_stick = false;
};
FePrompts CurrentFePrompts();  // any thread
void FeTouch(bool down, float u, float v);  // any thread: the finger in shares of the frame, down or lifted
// any thread, a moment after a tap: it came down on the highlighted button, still the same, for the pad's A; once
bool FeTakeAccept();

}  // namespace game
