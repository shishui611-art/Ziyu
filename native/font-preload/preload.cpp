// SPDX-License-Identifier: GPL-3.0-only
// One-shot Android font-cache preparation; no mount or policy changes.
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <pthread.h>
#include <android/log.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include "zygisk.hpp"

namespace {
constexpr const char *kModule = "/data/adb/modules/ziyu";
constexpr uint32_t kProtocol = 0x5a465032;
constexpr unsigned kLimit = 192;
constexpr size_t kPath = 512;
constexpr off_t kMaxFont = 128 * 1024 * 1024;
constexpr uint64_t kMaxMappings = 512ULL * 1024 * 1024;
struct Item { char path[kPath]; char identity[128]; uint64_t size; };
struct Request { uint32_t protocol, kind; uint64_t generation; };
struct Header { uint32_t protocol, count, omitted, reserved; uint64_t generation; };
struct Outcome { uint32_t protocol, invoked, failed, skipped, unavailable, denied; };
static_assert(sizeof(Item) == 648 && sizeof(Request) == 16 && sizeof(Header) == 24,
              "wire sizes must agree on both ABIs");
struct Snapshot {
    struct stat state{}, payload{}, proof{}, permission{}, metadata{};
    char boot[64]{};
};
struct Catalog {
    Snapshot identity{};
    Item items[kLimit]{};
    char allowed[131072]{};
    unsigned count = 0;
    unsigned omitted = 0;
    uint64_t mappingBytes = 0;
    uint64_t generation = 0;
    bool initialized = false, valid = false, reported[2]{};
};
static Catalog catalog;
static pthread_mutex_t catalogLock = PTHREAD_MUTEX_INITIALIZER;
// The companion cannot survive a kernel reboot. Read this once, under the lock.
static char companionBoot[64]{};

bool transfer(int fd, void *buffer, size_t size, bool sending) {
    auto *cursor = static_cast<char *>(buffer);
    while (size) {
        ssize_t n = sending ? send(fd, cursor, size, MSG_NOSIGNAL) : read(fd, cursor, size);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        cursor += n;
        size -= static_cast<size_t>(n);
    }
    return true;
}
void boundSocket(int fd) {
    timeval timeout{0, 150000};
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
}
bool readText(int dir, const char *path, char *out, size_t capacity) {
    int fd = openat(dir, path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return false;
    struct stat st{};
    bool ok = fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_size < static_cast<off_t>(capacity);
    size_t used = 0;
    while (ok && used < capacity - 1) {
        ssize_t n = read(fd, out + used, capacity - 1 - used);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { ok = false; break; }
        if (!n) break;
        used += static_cast<size_t>(n);
    }
    close(fd);
    out[used] = 0;
    return ok;
}
bool field(const char *text, const char *key, char *out, size_t capacity) {
    size_t keySize = strlen(key);
    for (const char *line = text; *line;) {
        const char *end = strchr(line, '\n');
        if (!end) end = line + strlen(line);
        if (static_cast<size_t>(end - line) > keySize &&
            !strncmp(line, key, keySize) && line[keySize] == '=') {
            size_t n = static_cast<size_t>(end - line) - keySize - 1;
            if (n && line[keySize + n] == '\r') --n;
            if (n >= capacity) return false;
            memcpy(out, line + keySize + 1, n);
            out[n] = 0;
            return true;
        }
        line = *end ? end + 1 : end;
    }
    return false;
}
bool equalField(const char *text, const char *key, const char *expected) {
    char value[128]{};
    return field(text, key, value, sizeof(value)) && !strcmp(value, expected);
}
bool sameStat(const struct stat &a, const struct stat &b) {
    return a.st_dev == b.st_dev && a.st_ino == b.st_ino && a.st_size == b.st_size &&
        a.st_mtim.tv_sec == b.st_mtim.tv_sec && a.st_mtim.tv_nsec == b.st_mtim.tv_nsec &&
        a.st_ctim.tv_sec == b.st_ctim.tv_sec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec;
}
bool sameSnapshot(const Snapshot &a, const Snapshot &b) {
    return !strcmp(a.boot, b.boot) && sameStat(a.state, b.state) && sameStat(a.payload, b.payload) &&
        sameStat(a.proof, b.proof) && sameStat(a.permission, b.permission) && sameStat(a.metadata, b.metadata);
}
void statIdentity(const struct stat &st, char *out, size_t capacity) {
    snprintf(out, capacity, "%llu:%llu:%lld:%lld:%lld",
        static_cast<unsigned long long>(st.st_dev), static_cast<unsigned long long>(st.st_ino),
        static_cast<long long>(st.st_size),
        static_cast<long long>(st.st_mtim.tv_sec) * 1000000000LL + st.st_mtim.tv_nsec,
        static_cast<long long>(st.st_ctim.tv_sec) * 1000000000LL + st.st_ctim.tv_nsec);
}
bool markerAbsent(int dir, const char *name) {
    struct stat st{};
    return fstatat(dir, name, &st, AT_SYMLINK_NOFOLLOW) < 0 && errno == ENOENT;
}
bool snapshot(int dir, Snapshot &out) {
    if (!markerAbsent(dir, "disable") || !markerAbsent(dir, "remove")) return false;
    if (!companionBoot[0]) {
        if (!readText(AT_FDCWD, "/proc/sys/kernel/random/boot_id", companionBoot, sizeof(companionBoot))) return false;
        companionBoot[strcspn(companionBoot, "\r\n")] = 0;
    }
    if (!companionBoot[0]) return false;
    memcpy(out.boot, companionBoot, sizeof(out.boot));
    if (fstatat(dir, "module.prop", &out.metadata, AT_SYMLINK_NOFOLLOW) || !S_ISREG(out.metadata.st_mode) ||
        fstatat(dir, "config/mount-backend.conf", &out.state, AT_SYMLINK_NOFOLLOW) || !S_ISREG(out.state.st_mode) ||
        fstatat(dir, ".luoshu-payload", &out.payload, AT_SYMLINK_NOFOLLOW) || !S_ISDIR(out.payload.st_mode) ||
        fstatat(dir, "config/mount-backend-verification.json", &out.proof, AT_SYMLINK_NOFOLLOW) || !S_ISREG(out.proof.st_mode) ||
        fstatat(dir, "config/font-preload-generation.conf", &out.permission, AT_SYMLINK_NOFOLLOW) || !S_ISREG(out.permission.st_mode)) return false;
    // Revalidate identities and disable/remove every time; reread metadata and
    // rebuild the catalog only when this complete bounded identity changes.
    if (catalog.initialized && sameSnapshot(out, catalog.identity)) return true;
    char meta[2048]{}, state[16384]{}, active[128]{};
    if (!readText(dir, "module.prop", meta, sizeof(meta)) || !equalField(meta, "id", "ziyu") ||
        !readText(dir, "config/mount-backend.conf", state, sizeof(state)) ||
        !equalField(state, "schema", "ziyu-mount-backend-v3") ||
        !equalField(state, "boot_id", out.boot) ||
        !field(state, "payload_font", active, sizeof(active)) || !active[0] || !strcmp(active, "default") ||
        !(equalField(state, "active_backend", "self") || equalField(state, "active_backend", "external")) ||
        !(equalField(state, "verification", "passed") || equalField(state, "verification", "partial"))) return false;
    char ready[131072]{}, token[256]{};
    if (!readText(dir, "config/font-preload-generation.conf", ready, sizeof(ready)) ||
        !equalField(ready, "schema", "ziyu-font-preload-generation-v2") ||
        !equalField(ready, "boot_id", out.boot)) return false;
    statIdentity(out.state, token, sizeof(token));
    char actual[256]{};
    if (!field(ready, "state_identity", actual, sizeof(actual)) || strcmp(token, actual)) return false;
    statIdentity(out.payload, token, sizeof(token));
    if (!field(ready, "payload_identity", actual, sizeof(actual)) || strcmp(token, actual)) return false;
    statIdentity(out.proof, token, sizeof(token));
    return field(ready, "proof_identity", actual, sizeof(actual)) && !strcmp(token, actual);
}
bool fontName(const char *name) {
    const char *suffix = strrchr(name, '.');
    return suffix && (!strcasecmp(suffix, ".ttf") || !strcasecmp(suffix, ".otf") ||
                      !strcasecmp(suffix, ".ttc") || !strcasecmp(suffix, ".font"));
}
bool component(const char *name) {
    return name[0] && strcmp(name, ".") && strcmp(name, "..") &&
        !strchr(name, '/') && !strchr(name, '\\') && !strchr(name, '\n') && !strchr(name, '\r');
}
bool relativeFontPath(const char *path) {
    if (!path[0] || path[0] == '/' || strlen(path) >= kPath || !fontName(path)) return false;
    char copy[kPath]{};
    snprintf(copy, sizeof(copy), "%s", path);
    char *save = nullptr;
    char *part = strtok_r(copy, "/", &save);
    const char *parts[] = {"system", "system_ext", "product", "vendor", "odm", "oem", "my_product",
        "my_engineering", "my_company", "my_preload", "my_region", "my_stock", "oplus_product",
        "oplus_engineering", "oplus_version", "oplus_region", "mi_ext", "cust", "hw_product", "oplus"};
    auto partition = [&](const char *name) {
        for (const char *candidate : parts) if (name && !strcmp(candidate, name)) return true;
        return false;
    };
    if (!partition(part) || strstr(path, "//")) return false;
    bool system = !strcmp(part, "system");
    char *next = strtok_r(nullptr, "/", &save);
    if (system && partition(next)) next = strtok_r(nullptr, "/", &save);
    if (!next || strcmp(next, "fonts")) return false;
    bool file = false;
    while ((next = strtok_r(nullptr, "/", &save))) {
        if (!component(next) || strchr(next, '|') || strchr(next, ':')) return false;
        file = true;
    }
    return file;
}

bool prepare(int dir, const Snapshot &before) {
    catalog.valid = false;
    catalog.reported[0] = catalog.reported[1] = false;
    catalog.count = catalog.omitted = 0;
    catalog.mappingBytes = 0;
    ++catalog.generation;
    catalog.initialized = true;
    catalog.identity = before;
    int payload = openat(dir, ".luoshu-payload", O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (payload < 0) return false;
    char real[4096]{};
    bool ok = realpath("/data/adb/modules/ziyu/.luoshu-payload", real) &&
        readText(dir, "config/font-preload-generation.conf", catalog.allowed, sizeof(catalog.allowed));
    char value[32]{};
    if (ok && field(catalog.allowed, "omitted_fonts", value, sizeof(value)))
        catalog.omitted = strtoul(value, nullptr, 10);
    // Consume the verifier's ordered records directly. readdir order used to
    // discard its CJK/Latin priority and spend the mapping budget on spare aliases.
    char *lineSave = nullptr;
    for (char *line = ok ? strtok_r(catalog.allowed, "\n", &lineSave) : nullptr;
         line; line = strtok_r(nullptr, "\n", &lineSave)) {
        if (strncmp(line, "font=", 5)) continue;
        char *fields[4]{};
        char *fieldSave = nullptr;
        unsigned n = 0;
        for (char *f = strtok_r(line + 5, "|", &fieldSave); f && n < 4;
             f = strtok_r(nullptr, "|", &fieldSave)) fields[n++] = f;
        if (n != 4 || fields[0][0] != '/' || !relativeFontPath(fields[0] + 1) ||
            !relativeFontPath(fields[1]) || strlen(fields[3]) >= sizeof(Item::identity)) {
            ++catalog.omitted; continue;
        }
        struct stat source{};
        char full[4096]{}, resolved[4096]{}, sourceIdentity[128]{};
        bool valid = !fstatat(payload, fields[1], &source, 0) && S_ISREG(source.st_mode) &&
            source.st_size > 0 && source.st_size <= kMaxFont &&
            snprintf(full, sizeof(full), "%s/.luoshu-payload/%s", kModule, fields[1]) < static_cast<int>(sizeof(full)) &&
            realpath(full, resolved) && !strncmp(resolved, real, strlen(real)) && resolved[strlen(real)] == '/';
        if (valid) { statIdentity(source, sourceIdentity, sizeof(sourceIdentity)); valid = !strcmp(sourceIdentity, fields[2]); }
        if (!valid || catalog.count == kLimit ||
            catalog.mappingBytes + static_cast<uint64_t>(source.st_size) > kMaxMappings) {
            ++catalog.omitted; continue;
        }
        Item &item = catalog.items[catalog.count++];
        memset(&item, 0, sizeof(item));
        snprintf(item.path, sizeof(item.path), "%s", fields[0]);
        snprintf(item.identity, sizeof(item.identity), "%s", fields[3]);
        item.size = static_cast<uint64_t>(source.st_size);
        catalog.mappingBytes += item.size;
    }
    close(payload);
    Snapshot after{};
    ok = ok && catalog.count && snapshot(dir, after) && sameSnapshot(before, after);
    if (ok) { catalog.identity = after; catalog.valid = true; }
    else __android_log_print(ANDROID_LOG_WARN, "ZiyuFontPreload", "catalog unavailable: generation changed or no verified font remains");
    return ok;
}
bool report(int dir, const Snapshot &generation, const Outcome &outcome, unsigned listed, unsigned omitted) {
    // A callback receipt, not proof that a UI rendered correctly. One per generation/ABI.
    char filename[96]{}, temp[112]{};
    snprintf(filename, sizeof(filename), "config/font-preload-%s%s.conf", ZIYU_PRELOAD_ABI, outcome.denied ? "-denylist" : "");
    snprintf(temp, sizeof(temp), "%s.tmp.%d", filename, getpid());
    char text[768]{};
    char stateIdentity[256]{};
    statIdentity(generation.state, stateIdentity, sizeof(stateIdentity));
    int size = snprintf(text, sizeof(text),
        "schema=ziyu-font-preload-callback-v1\nboot_id=%s\nabi=%s\nstate=callback-observed\nstate_identity=%s\n"
        "listed=%u\ncatalog_omitted=%u\ninvoked=%u\nfailed=%u\nskipped=%u\nmethod_unavailable=%u\n"
        "process_on_denylist=%u\nscope=first-callback-per-denylist-scope;not-render-validation\n",
        generation.boot, ZIYU_PRELOAD_ABI, stateIdentity, listed, omitted, outcome.invoked, outcome.failed, outcome.skipped, outcome.unavailable, outcome.denied);
    int fd = openat(dir, temp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_NOFOLLOW, 0600);
    if (fd < 0) return false;
    bool ok = write(fd, text, size) == size;
    close(fd);
    if (!ok || renameat(dir, temp, dir, filename)) { unlinkat(dir, temp, 0); return false; }
    return true;
}
void companion(int client) {
    boundSocket(client);
    Request request{};
    if (!transfer(client, &request, sizeof(request), false) || request.protocol != kProtocol ||
        (request.kind != 1 && request.kind != 2)) { close(client); return; }
    Outcome outcome{};
    if (request.kind == 2 && (!transfer(client, &outcome, sizeof(outcome), false) ||
        outcome.protocol != kProtocol || outcome.denied > 1)) { close(client); return; }
    int dir = open(kModule, O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    Snapshot identity{};
    pthread_mutex_lock(&catalogLock);
    bool ready = dir >= 0 && snapshot(dir, identity);
    if (request.kind == 1) {
        if (ready) {
            if (!catalog.initialized || !sameSnapshot(identity, catalog.identity)) ready = prepare(dir, identity);
            else ready = catalog.valid;
        }
        Header header{kProtocol, ready ? catalog.count : 0, ready ? catalog.omitted : 0, 0, catalog.generation};
        if (transfer(client, &header, sizeof(header), true) && header.count)
            transfer(client, catalog.items, header.count * sizeof(Item), true);
    } else if (ready && catalog.valid && request.generation == catalog.generation &&
               sameSnapshot(identity, catalog.identity) && !catalog.reported[outcome.denied] &&
               outcome.invoked <= catalog.count && outcome.failed <= catalog.count && outcome.skipped <= catalog.count &&
               outcome.invoked + outcome.failed + outcome.skipped <= catalog.count) {
        catalog.reported[outcome.denied] = report(dir, identity, outcome, catalog.count, catalog.omitted);
    }
    pthread_mutex_unlock(&catalogLock);
    if (dir >= 0) close(dir);
    close(client);
}

class Preloader : public zygisk::ModuleBase {
    zygisk::Api *api = nullptr;
    JNIEnv *env = nullptr;
public:
    void onLoad(zygisk::Api *a, JNIEnv *e) override { api = a; env = e; }
    void preAppSpecialize(zygisk::AppSpecializeArgs *) override {
        // Include system-UID apps: the reported failure also affects Settings/launcher.
        // No persistent hook is installed, so the helper library can be unloaded.
        preload();
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
    void preServerSpecialize(zygisk::ServerSpecializeArgs *) override {
        api->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }
    void preload() {
        if (!env || env->ExceptionCheck()) return; // Never clear an exception we did not create.
        int fd = api->connectCompanion();
        if (fd < 0) return;
        boundSocket(fd);
        Request request{kProtocol, 1, 0};
        if (!transfer(fd, &request, sizeof(request), true)) { close(fd); return; }
        Header header{};
        if (!transfer(fd, &header, sizeof(header), false) || header.protocol != kProtocol || header.count > kLimit) {
            close(fd); return;
        }
        if (!header.count) { close(fd); return; }
        // Receive the bounded path list before starting per-file JNI work.
        auto *items = static_cast<Item *>(calloc(header.count, sizeof(Item)));
        if (!items) { close(fd); return; }
        if (!transfer(fd, items, header.count * sizeof(Item), false)) { free(items); close(fd); return; }
        // JNI/font I/O may exceed the socket timeout. Do not keep the root
        // companion waiting while fonts load; report on a separate connection.
        close(fd);
        Outcome outcome{kProtocol, 0, 0, 0, 0, (api->getFlags() & zygisk::PROCESS_ON_DENYLIST) ? 1u : 0u};
        jclass typeface = env->FindClass("android/graphics/Typeface");
        jmethodID warm = typeface ? env->GetStaticMethodID(typeface, "nativeWarmUpCache", "(Ljava/lang/String;)V") : nullptr;
        if (!warm || env->ExceptionCheck()) {
            env->ExceptionClear();
            outcome.unavailable = 1;
        } else {
            for (unsigned i = 0; i < header.count; ++i) {
                const Item &item = items[i];
                struct stat visible{};
                char visibleIdentity[128]{};
                if (item.path[kPath - 1] || item.identity[sizeof(item.identity) - 1] ||
                    item.path[0] != '/' || !relativeFontPath(item.path + 1) || stat(item.path, &visible) ||
                    !S_ISREG(visible.st_mode) || static_cast<uint64_t>(visible.st_size) != item.size) {
                    ++outcome.skipped; continue;
                }
                statIdentity(visible, visibleIdentity, sizeof(visibleIdentity));
                // Compare the PID-1 verified mounted file, not the private source
                // inode: providers may legitimately copy or overlay the payload.
                if (strcmp(visibleIdentity, item.identity)) { ++outcome.skipped; continue; }
                jstring path = env->NewStringUTF(item.path);
                if (path && !env->ExceptionCheck()) env->CallStaticVoidMethod(typeface, warm, path);
                if (!path || env->ExceptionCheck()) { env->ExceptionClear(); ++outcome.failed; }
                else ++outcome.invoked;
                if (path) env->DeleteLocalRef(path);
            }
        }
        if (typeface) env->DeleteLocalRef(typeface);
        fd = api->connectCompanion();
        if (fd >= 0) {
            boundSocket(fd);
            request = {kProtocol, 2, header.generation};
            if (transfer(fd, &request, sizeof(request), true)) transfer(fd, &outcome, sizeof(outcome), true);
            close(fd);
        }
        __android_log_print(outcome.failed || outcome.unavailable || outcome.skipped || header.omitted ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG,
            "ZiyuFontPreload", "abi=%s listed=%u catalog_omitted=%u invoked=%u failed=%u skipped=%u method_unavailable=%u",
            ZIYU_PRELOAD_ABI, header.count, header.omitted, outcome.invoked, outcome.failed, outcome.skipped, outcome.unavailable);
        free(items);
    }
};
} // namespace
REGISTER_ZYGISK_MODULE(Preloader)
REGISTER_ZYGISK_COMPANION(companion)
