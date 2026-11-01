#pragma once

// the in-game settings menu (src/game/settings_menu.cpp)
namespace game {

// any thread (the overlay's ☰, GameActivity.nativeOpenSettings): open the settings, -1 their categories, else that
// settings::Category. In a race the game is paused first, and the settings open over the pause menu
void RequestOpenSettings(int category);
// any thread (native/touch.cpp, port 0's pad): true once for a request in a race with no menu up, to press START
bool SettingsTakePause();
// any thread (GameActivity.nativeCardPause): the ☰ card opens (true: pause a race with no menu up, by one START) or
// closes on Resume (false: START again only if the card's START paused it and that pause menu is still up)
void RequestCardPause(bool pause);

}  // namespace game
