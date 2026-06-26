# Toolchain image for building the zh-CN LibreOffice WASM engine (#66).
#
# This image carries the BUILD DEPENDENCIES. The heavy build runs at `docker run`
# time via build.sh so the >=64 GB RAM requirement is a runtime concern and the
# artifacts mount out to the host. Run on a big machine (NOT standard CI / not the
# agent sandbox). Expect to iterate — LO WASM builds are finicky.
#
# Usage:
#   docker build -t lowa-zhcn-build desktop/lowa-build
#   docker run --rm -v "$PWD/desktop/lowa-build/out:/work/out" \
#       -e CORES=$(nproc) lowa-zhcn-build
#   # -> out/{soffice.js,soffice.wasm,soffice.data,soffice.data.js.metadata}
#
# A 64 GB+ RAM host is mandatory for the final link. Consider a temporary
# high-memory cloud VM if no local machine qualifies.

FROM ubuntu:22.04

ENV DEBIAN_FRONTEND=noninteractive

# LibreOffice + Qt + emscripten build prerequisites. (LO's build pulls many more
# via its bundled externals; this is the host-side baseline. Add packages here as
# the build complains — iteration expected.)
RUN apt-get update && apt-get install -y --no-install-recommends \
      git build-essential ccache python3 python3-pip \
      autoconf automake libtool pkg-config bison flex gperf \
      zip unzip wget curl xz-utils nasm \
      libxml2-utils xsltproc \
      openjdk-17-jdk-headless \
      ca-certificates locales \
    && rm -rf /var/lib/apt/lists/*

# zh-CN locale on the build host (some build steps are locale-sensitive).
RUN sed -i 's/# zh_CN.UTF-8/zh_CN.UTF-8/' /etc/locale.gen && locale-gen

WORKDIR /work
COPY autogen.input build.sh /work/lowa-build/
RUN chmod +x /work/lowa-build/build.sh

# Build into /work, emit artifacts to /work/out (mount this out).
ENV ROOT=/work OUT=/work/out
ENTRYPOINT ["/work/lowa-build/build.sh"]
