// Linux stand ins for native/stats.cpp and native/activity.cpp: no JVM, no Android thermal or battery sources, no
// launcher (the game folder is MW_GAME_DIR, linux/game_files.cpp)
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace host {

float VulkanGpuLoad();  // native/vulkan.cpp

std::filesystem::path LauncherGameDir() { return {}; }
void PhoneRumble(uint16_t, uint16_t) {}
std::optional<std::string> ShellRow(int, int) { return std::nullopt; }  // no overlay: the file's keys (settings_rows.cpp)
void SamplePerf() {}
float CpuLoad() { return -1; }
float GpuLoad() { return VulkanGpuLoad(); }
float CpuTemp() { return -1; }
float GpuTemp() { return -1; }
float BatteryTemp() { return -1; }

}  // namespace host
