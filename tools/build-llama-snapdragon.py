#!/usr/bin/env python3
"""Cross-compile llama.cpp with the Hexagon NPU backend for this app.

The Hexagon skels need Qualcomm's hexagon-clang, which only exists for x86 Linux,
so the build runs inside the pinned snapdragon-toolchain image. One small source
patch is applied first: ggml_fopen learns the "fd:N" form so the app can hand over
an already-open descriptor for a Storage Access Framework document. Everything it
produces is copied into app/src/main/jniLibs/arm64-v8a (gitignored) and the
headers into build/llama-snapdragon/include.

Run once on a machine with Docker Desktop running:

    python tools/build-llama-snapdragon.py

The image is about 10 GB and the compile takes roughly 15 minutes.
"""

import argparse
import hashlib
import logging
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request

# Same revision the previous in-Gradle FetchContent build pinned.
REVISION = "3057bb66c86c46d5781e50e85462a760ba7d1feb"
ARCHIVE_SHA256 = "624ecdc13c247c87a2bd315d365542a24639510b4e40d1b51deae9b55634e5cc"
ARCHIVE_URL = f"https://codeload.github.com/ggml-org/llama.cpp/tar.gz/{REVISION}"
IMAGE = "ghcr.io/snapdragon-toolchain/arm64-android:v0.7"

# The DSP-side skels, picked by Hexagon architecture at runtime. v81 is this phone
# (SM8850); the others cost 3 MB together and cover the rest of the range.
HTP_SKELS = ["v73", "v75", "v79", "v81"]
LIBRARIES = [
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-hexagon.so",
] + [f"libggml-htp-{skel}.so" for skel in HTP_SKELS]
HEADERS = ["llama.h", "llama-cpp.h", "ggml.h", "ggml-alloc.h", "ggml-backend.h",
           "ggml-cpu.h", "ggml-opt.h", "gguf.h"]

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(ROOT, "build", "llama-snapdragon")
SOURCE = os.path.join(WORK, "src")
JNI_LIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs", "arm64-v8a")
INCLUDE = os.path.join(WORK, "include")

log = logging.getLogger("build-llama")

# GGML_OPENCL stays off: the NPU carries every layer and the Adreno backend
# would add 13 MB plus an OpenCL probe on every model load.
# max-page-size keeps the shared objects loadable on 16 KB-page Android.
CMAKE_OPTIONS = [
    # The toolchain image ships Ninja, not Make.
    "-G", "Ninja",
    "-DCMAKE_BUILD_TYPE=Release",
    "-DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake",
    "-DANDROID_ABI=arm64-v8a",
    "-DANDROID_PLATFORM=android-28",
    "-DBUILD_SHARED_LIBS=ON",
    "-DGGML_HEXAGON=ON",
    "-DHEXAGON_SDK_ROOT=$HEXAGON_SDK_ROOT",
    # The Hexagon SDK's cmake helpers read this; without it they fail to configure.
    "-DPREBUILT_LIB_DIR=android_aarch64",
    "-DGGML_OPENCL=OFF",
    "-DGGML_OPENMP=OFF",
    "-DGGML_LLAMAFILE=OFF",
    "-DGGML_NATIVE=OFF",
    "-DLLAMA_BUILD_COMMON=OFF",
    "-DLLAMA_BUILD_TESTS=OFF",
    "-DLLAMA_BUILD_EXAMPLES=OFF",
    "-DLLAMA_BUILD_TOOLS=OFF",
    "-DLLAMA_BUILD_SERVER=OFF",
    "-DLLAMA_BUILD_MTMD=OFF",
    "-DLLAMA_OPENSSL=OFF",
    "-DLLAMA_CURL=OFF",
    '-DCMAKE_C_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -O3"',
    '-DCMAKE_CXX_FLAGS="-march=armv8.7a+fp16+dotprod+i8mm -O3"',
    '-DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"',
]


def fetch_source():
    """Download and unpack the pinned llama.cpp tarball, verifying its checksum."""
    if os.path.isfile(os.path.join(SOURCE, "CMakeLists.txt")):
        log.info("Reusing extracted source at %s", SOURCE)
        return
    os.makedirs(WORK, exist_ok=True)
    archive = os.path.join(WORK, "llama-source.tar.gz")
    shared = os.path.join(ROOT, "build", "llama-source.tar.gz")
    if not os.path.isfile(archive) and os.path.isfile(shared):
        log.info("Reusing %s", shared)
        shutil.copy2(shared, archive)
    if not os.path.isfile(archive):
        log.info("Downloading llama.cpp %s", REVISION[:12])
        urllib.request.urlretrieve(ARCHIVE_URL, archive)
    digest = hashlib.sha256()
    with open(archive, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    if digest.hexdigest() != ARCHIVE_SHA256:
        raise SystemExit(f"Checksum mismatch for {archive}. Delete it and retry.")
    log.info("Extracting source")
    if os.path.isdir(SOURCE):
        shutil.rmtree(SOURCE)
    with tarfile.open(archive) as tar:
        tar.extractall(WORK)
    os.rename(os.path.join(WORK, f"llama.cpp-{REVISION}"), SOURCE)


FOPEN_ORIGINAL = "#else\n    return fopen(fname, mode);\n#endif"
FOPEN_PATCHED = """#else
    // Calico: "fd:N" adopts an already-open descriptor. The model is an Android
    // Storage Access Framework document, which scoped storage refuses to reopen by
    // path (even through /proc/self/fd), so the app hands over the descriptor instead.
    if (strncmp(fname, "fd:", 3) == 0) {
        int fd = dup(atoi(fname + 3));
        return fd < 0 ? NULL : fdopen(fd, mode);
    }
    return fopen(fname, mode);
#endif"""


def patch_source():
    """Teach ggml_fopen the fd:N form. Every model open (gguf header and weights) goes through it."""
    path = os.path.join(SOURCE, "ggml", "src", "ggml.c")
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if FOPEN_PATCHED in text:
        return
    if FOPEN_ORIGINAL not in text:
        raise SystemExit("ggml_fopen looks different from the pinned revision; refusing to patch blindly.")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text.replace(FOPEN_ORIGINAL, FOPEN_PATCHED, 1))
    log.info("Patched ggml_fopen to accept fd:N")


def run_docker(jobs):
    """Configure, build and install llama.cpp inside the toolchain container."""
    # A cache from a different generator or toolchain makes cmake refuse to configure.
    # The removal runs container-side: Windows keeps handles on bind-mounted files.
    stale = "if [ -f /workspace/build-npu/CMakeCache.txt ] && " \
            "! grep -q CMAKE_GENERATOR:INTERNAL=Ninja /workspace/build-npu/CMakeCache.txt; " \
            "then rm -rf /workspace/build-npu; fi"
    script = " && ".join([
        stale,
        "cmake -B /workspace/build-npu " + " ".join(CMAKE_OPTIONS) + " /workspace",
        # The DSP skels are separate ExternalProject targets, so they are named
        # explicitly. The default target would also build the llama CLI, which needs
        # LLAMA_BUILD_COMMON and none of which this app uses.
        f"cmake --build /workspace/build-npu -j {jobs} --target llama "
        + " ".join(f"htp-{skel}" for skel in HTP_SKELS),
    ])
    command = [
        "docker", "run", "--rm",
        "--volume", f"{SOURCE}:/workspace",
        "--workdir", "/workspace",
        "--platform", "linux/amd64",
        IMAGE, "bash", "-c", script,
    ]
    log.info("Building in %s (this takes a while)", IMAGE)
    # MSYS/Git Bash would rewrite the container-side absolute paths otherwise.
    environment = dict(os.environ, MSYS_NO_PATHCONV="1", MSYS2_ARG_CONV_EXCL="*")
    result = subprocess.run(command, env=environment)
    if result.returncode != 0:
        raise SystemExit("Docker build failed. Is Docker Desktop running?")


def find(name, roots):
    """Locate one built artefact; the skels land beside their ExternalProject."""
    for root in roots:
        for directory, _, files in os.walk(root):
            if name in files:
                return os.path.join(directory, name)
    return None


def collect():
    """Copy the built libraries and headers into the app tree."""
    build = os.path.join(SOURCE, "build-npu")
    os.makedirs(JNI_LIBS, exist_ok=True)
    os.makedirs(INCLUDE, exist_ok=True)
    for name in LIBRARIES:
        source = find(name, [os.path.join(build, "bin"), build])
        if not source:
            raise SystemExit(f"{name} was not built. Check the build log above.")
        shutil.copy2(source, os.path.join(JNI_LIBS, name))
        log.info("  %s (%.1f MB)", name, os.path.getsize(source) / 1e6)
    for name in HEADERS:
        source = find(name, [os.path.join(SOURCE, "include"), os.path.join(SOURCE, "ggml", "include")])
        if not source:
            raise SystemExit(f"Header {name} was not found in the source tree.")
        shutil.copy2(source, os.path.join(INCLUDE, name))
    with open(os.path.join(JNI_LIBS, "LLAMA_REVISION"), "w") as handle:
        handle.write(REVISION + "\n")


def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jobs", "-j", type=int, default=os.cpu_count() or 4,
                        help="parallel compile jobs (default: CPU count)")
    parser.add_argument("--skip-build", action="store_true",
                        help="only copy libraries from an earlier build")
    args = parser.parse_args()

    if not args.skip_build:
        if shutil.which("docker") is None:
            raise SystemExit("docker was not found on PATH. Install Docker Desktop.")
        fetch_source()
        patch_source()
        run_docker(args.jobs)
    collect()
    log.info("\nInstalled %d libraries into %s", len(LIBRARIES), JNI_LIBS)
    log.info("Headers in %s", INCLUDE)


if __name__ == "__main__":
    sys.exit(main())
