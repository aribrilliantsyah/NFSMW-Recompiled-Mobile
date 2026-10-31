#pragma once
#include <filesystem>
#include <functional>

#include <toml++/toml.hpp>

// mostwanted.toml has several editors (the game's app::Save, the launcher shell, the overlay, the Linux host's CONTROLS
// rows): each writes only the keys it changed, into the file as it is now, under SettingsLock. settings_file.cpp
namespace game {

// the file read again under its lock, change(t, broken) made on it, written through <file>.tmp renamed over it. A
// missing file is an empty table. One that cannot be parsed (or read) is copied to <file>.bad first, and change gets an
// empty table with broken true; when that copy fails nothing is written. change returns whether to write. True only
// when the file was written; else it is as it was
bool UpdateSettingsFile(const std::filesystem::path& file, const std::function<bool(toml::table&, bool broken)>& change);

// from's leaves into into: a table both have ([keys]) keeps the entries from lacks
void MergeLeaves(toml::table& into, const toml::table& from);

// the leaves of now that base lacks or holds another value for (base null: all of them), as a table of their own
toml::table ChangedLeaves(const toml::table& now, const toml::table* base);

// app::Save's: the leaves of now (the run's settings) that differ from saved (as the run last read or wrote them) into
// the file, the rest of it (the launcher's newer values too) as it is; a broken file gets all of now. saved then
// holds what was written. True when there was nothing to write or it was written
bool SaveChanged(const std::filesystem::path& file, const toml::table& now, toml::table& saved);

}  // namespace game
