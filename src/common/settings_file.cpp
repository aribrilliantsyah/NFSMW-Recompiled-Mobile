#include "common/settings_file.h"

#include <fstream>
#include <system_error>

#include "common/settings_lock.h"
#include "core/log.h"

namespace game {

bool UpdateSettingsFile(const std::filesystem::path& file, const std::function<bool(toml::table&, bool)>& change) {
    SettingsLock lock(file);
    if (!lock.ok()) return false;  // another editor may be writing: no write over it
    toml::table t;
    bool broken = false;
    std::error_code ec;
    // a file we cannot even look at may still be there: not a missing file to write fresh
    bool exists = std::filesystem::exists(file, ec);
    if (ec) return false;
    if (exists) {
        try {
            t = toml::parse_file(file.string());
        } catch (const toml::parse_error& e) {
            // parse_file says so for a file it cannot open too: the copy fails then, and nothing is written
            std::filesystem::copy_file(file, file.string() + ".bad", std::filesystem::copy_options::overwrite_existing,
                                       ec);
            if (ec) {
                LOGE("settings: %s is broken and cannot be kept as .bad: not saved", file.c_str());
                return false;
            }
            LOGW("settings: %s is broken (%s), kept as .bad", file.c_str(), std::string(e.description()).c_str());
            t = toml::table();
            broken = true;
        }
    }
    if (!change(t, broken)) return false;
    std::filesystem::path tmp = file.string() + ".tmp";
    {
        std::ofstream f(tmp);
        f << t << "\n";
        f.flush();
        if (!f) {
            std::filesystem::remove(tmp, ec);
            return false;
        }
    }
    std::filesystem::rename(tmp, file, ec);
    return !ec;
}

void MergeLeaves(toml::table& into, const toml::table& from) {
    for (auto&& [key, value] : from) {
        toml::table* have = into[key].as_table();
        if (have && value.is_table()) MergeLeaves(*have, *value.as_table());
        else into.insert_or_assign(key, value);
    }
}

toml::table ChangedLeaves(const toml::table& now, const toml::table* base) {
    toml::table out;
    for (auto&& [key, value] : now) {
        const toml::node* was = base ? base->get(key) : nullptr;
        if (const toml::table* sub = value.as_table()) {
            toml::table inner = ChangedLeaves(*sub, was ? was->as_table() : nullptr);
            if (!inner.empty()) out.insert(key, std::move(inner));
        } else if (!was || toml::node_view<const toml::node>(was) != toml::node_view<const toml::node>(value)) {
            out.insert(key, value);
        }
    }
    return out;
}

bool SaveChanged(const std::filesystem::path& file, const toml::table& now, toml::table& saved) {
    toml::table changed = ChangedLeaves(now, &saved);
    if (changed.empty()) return true;
    bool ok = UpdateSettingsFile(file, [&](toml::table& t, bool broken) {
        MergeLeaves(t, broken ? now : changed);
        return true;
    });
    if (ok) MergeLeaves(saved, changed);
    return ok;
}

}  // namespace game
