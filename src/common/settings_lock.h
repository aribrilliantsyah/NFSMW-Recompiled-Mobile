#pragma once
#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <filesystem>
#include <string>

#include "core/log.h"

// The settings file has two editors, the game (app::Save) and the launcher shell (TomlSettings.update, in the game's
// own :game process too): each holds this lock on "<file>.lock" beside it across its read, merge and rename, so
// neither writes back over a change the other made in between. An open file description lock (F_OFD_SETLKW): unlike a
// process-owned record lock it conflicts with Java's FileChannel.lock (a record lock) in the same process as well as
// in another (fcntl(2)). Held while the fd is open; ok() false when it could not be taken, and then nothing is written
namespace game {

#ifndef F_OFD_SETLKW
#define F_OFD_SETLKW 38  // Linux's, as glibc and bionic define it
#endif

class SettingsLock {
public:
    explicit SettingsLock(const std::filesystem::path& file) {
        std::string path = file.string() + ".lock";
        fd_ = ::open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0660);
        if (fd_ < 0) {
            LOGE("settings: cannot open %s: %s; not saved", path.c_str(), std::strerror(errno));
            return;
        }
        struct flock l = {};
        l.l_type = F_WRLCK;
        l.l_whence = SEEK_SET;  // l_pid 0, as OFD locks require
        int r;
        while ((r = ::fcntl(fd_, F_OFD_SETLKW, &l)) < 0 && errno == EINTR) {
        }
        if (r < 0) {
            LOGE("settings: cannot lock %s: %s; not saved", path.c_str(), std::strerror(errno));
            ::close(fd_);
            fd_ = -1;
        }
    }
    ~SettingsLock() {
        if (fd_ >= 0) ::close(fd_);  // the last close of the description lets the lock go
    }
    bool ok() const { return fd_ >= 0; }
    SettingsLock(const SettingsLock&) = delete;
    SettingsLock& operator=(const SettingsLock&) = delete;

private:
    int fd_ = -1;
};

}  // namespace game
