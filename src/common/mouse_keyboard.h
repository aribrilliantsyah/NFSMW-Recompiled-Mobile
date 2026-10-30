#pragma once
#include "core/input.h"

// a keyboard and mouse plugged into the phone as the controller on port 0, and the launcher shell's touch overlay and
// the controllers' mapping (native/touch.cpp, native/input.cpp)
namespace input {

void ReleaseCursor();     // the game lost the input: held keys wait for release
void Keyboard(Pad& pad);  // held keys onto port 0's pad
// the keys' bindings, [keys] in the settings file (action id to key names, comma separated); after the settings load
void LoadBindings();
// the launcher's controller mapping, [padmap] of the settings file's table (a toml::table); before boot
void LoadPadMap(const void* table);

void SeenPad(const Pad& pad);  // a controller's report before the keys join it
bool KeyboardActive();         // keyboard or mouse used last, not a controller

// any thread (GameActivity's JNI, native/activity.cpp): the touch overlay's pad, merged into port 0. buttons are the
// pad's bits (dpad up down left right, start, back, thumbs), face A B X Y black white as bits 0-5, the triggers 0 to
// 255, the sticks -32768 to 32767 with y up
void SetTouchPad(int buttons, int face, int lt, int rt, int lx, int ly);
void SetTouchPadRight(int rx, int ry);
// a controller Android passes on with no device SDL knows: its key codes and axes, as Android gives them
void LooseKey(int code, bool down);
void LooseAxes(float lx, float ly, float rx, float ry, float lt, float rt, float hx, float hy);
void LooseRelease();  // the app lost the focus: the ups go elsewhere

}  // namespace input
