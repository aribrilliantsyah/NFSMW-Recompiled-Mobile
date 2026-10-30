#pragma once
// x86-64 Linux build: src/d3d/render_thread.cpp's NEON compares through SIMDe (SSE underneath)
#define SIMDE_ENABLE_NATIVE_ALIASES
#include <simde/arm/neon.h>
