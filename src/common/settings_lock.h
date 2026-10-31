#pragma once
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>

#include <filesystem>
#include <string>

// The settings file has two editors, the game (app::Save) and the launcher shell (TomlSettings.update): each holds
// this lock, a POSIX record lock on "<file>.lock" beside it (Java's FileChannel.lock is one too), across its read,
// merge and rename, so neither writes back over a change the other made in between
namespace game {

class SettingsLock {
public:
    explicit SettingsLock(const std::filesystem::path& file) {
        fd_ = ::open((file.string() + ".lock").c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0660);
        if (fd_ < 0) return;
        struct flock l = {};
        l.l_type = F_WRLCK;
        l.l_whence = SEEK_SET;
        while (::fcntl(fd_, F_SETLKW, &l) < 0) {
            if (errno == EINTR) continue;
            ::close(fd_);  // no lock to be had: write as before, unlocked
            fd_ = -1;
            break;
        }
    }
    ~SettingsLock() {
        if (fd_ >= 0) ::close(fd_);  // closing lets the lock go
    }
    SettingsLock(const SettingsLock&) = delete;
    SettingsLock& operator=(const SettingsLock&) = delete;

private:
    int fd_ = -1;
};

}  // namespace game
