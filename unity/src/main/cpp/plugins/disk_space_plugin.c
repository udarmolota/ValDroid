// ARM64 build of Valheim's DiskSpacePlugin (the shipped one is x86_64 Linux): Valheim checks free
// space before saving and aborts the save when the P/Invoke fails. Same contract as the original:
// statvfs(path) -> f_bavail * f_frsize, -1 on error.
#include <stdint.h>
#include <sys/statvfs.h>

__attribute__((visibility("default"))) int64_t get_free_space(const char* path)
{
    struct statvfs st;
    if (statvfs(path, &st) != 0)
        return -1;
    return (int64_t)st.f_bavail * (int64_t)st.f_frsize;
}
