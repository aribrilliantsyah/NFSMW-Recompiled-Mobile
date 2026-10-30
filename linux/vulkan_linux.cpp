// native/vulkan.cpp as it is, on Linux: its CreateSurface waits for Android's window property, which SDL sets only
// on Android; here the Wayland surface's stands for it (run with SDL_VIDEODRIVER=wayland)
#include <SDL3/SDL.h>
#undef SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER
#define SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER SDL_PROP_WINDOW_WAYLAND_SURFACE_POINTER
#include "../native/vulkan.cpp"
