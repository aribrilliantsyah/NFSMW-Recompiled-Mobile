// Linux stand in for native/game_files.cpp: the extracted disc in MW_GAME_DIR (default.xbe beside NFS/), its xbe
// checked against the one this build was recompiled from
#include <cstdlib>
#include <fstream>
#include <iterator>

#include "app/app.h"
#include "core/log.h"
#include "image_symbols.h"
#include "kernel/crypto.h"

extern const char kImageSha1[];

namespace app {

namespace {

std::string Sha1(const std::vector<uint8_t>& data) {
    alignas(8) uint8_t ctx[128];
    uint8_t digest[20];
    crypto::ShaInit(ctx);
    crypto::ShaUpdate(ctx, data.data(), data.size());
    crypto::ShaFinal(ctx, digest);
    std::string hex;
    for (uint8_t b : digest) {
        hex += "0123456789abcdef"[b >> 4];
        hex += "0123456789abcdef"[b & 15];
    }
    return hex;
}

}  // namespace

bool FindGame(GameFiles& game) {
    const char* dir = std::getenv("MW_GAME_DIR");
    if (!dir || !*dir) {
        LOGE("game: set MW_GAME_DIR to the extracted disc (default.xbe and NFS/)");
        return false;
    }
    std::filesystem::path path(dir);
    std::ifstream f(path / IMAGE_XBE, std::ios::binary);
    if (!f) {
        LOGE("game: no %s in %s", IMAGE_XBE, dir);
        return false;
    }
    std::vector<uint8_t> xbe((std::istreambuf_iterator<char>(f)), {});
    std::string sha = Sha1(xbe);
    LOGR("Game: %s in %s, sha1 %s%s", IMAGE_XBE, dir, sha.c_str(), sha == kImageSha1 ? "" : ", another release");
    if (sha != kImageSha1) return false;
    game = {path, std::move(xbe)};
    return true;
}

}  // namespace app
