"""Create a module ZIP with Android executable modes preserved on Windows."""
import os
import sys
import zipfile

stage, out = sys.argv[1], sys.argv[2]
exec_dirs = {"system/bin", "common/python/bin", "meta", "scripts"}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for root, dirs, files in os.walk(stage):
        dirs.sort()
        for name in dirs:
            relative = os.path.relpath(os.path.join(root, name), stage).replace(os.sep, "/")
            info = zipfile.ZipInfo(relative + "/")
            info.create_system = 3
            info.external_attr = (0o40755 << 16) | 0x10
            archive.writestr(info, b"")
        for name in sorted(files):
            path = os.path.join(root, name)
            relative = os.path.relpath(path, stage).replace(os.sep, "/")
            parent = relative.rpartition("/")[0]
            executable = (name.endswith((".sh", ".py")) or parent in exec_dirs
                          or (parent == "common" and "." not in name))
            info = zipfile.ZipInfo(relative)
            info.create_system = 3
            info.external_attr = (0o100755 if executable else 0o100644) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with open(path, "rb") as source:
                archive.writestr(info, source.read())
print("wrote", out, os.path.getsize(out), "bytes")
