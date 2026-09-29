FROM ghcr.io/eivarin/coreemu:9.2.1 AS curl-builder

ARG CURL_VERSION=8.22.0
ARG NGHTTP3_VERSION=1.18.0
ARG NGTCP2_VERSION=1.25.0

RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt-get update && apt-get install -y --no-install-recommends \
    build-essential ca-certificates wget pkg-config \
    libgnutls28-dev libnghttp2-dev zlib1g-dev

# Keep the custom libraries private to curl, including at runtime.
ENV PKG_CONFIG_PATH=/opt/curl-http3/lib/pkgconfig
ENV LDFLAGS="-Wl,-rpath,/opt/curl-http3/lib"
WORKDIR /build/curl

RUN wget -q https://github.com/ngtcp2/nghttp3/releases/download/v${NGHTTP3_VERSION}/nghttp3-${NGHTTP3_VERSION}.tar.gz && \
    tar -xzf nghttp3-${NGHTTP3_VERSION}.tar.gz && \
    cd nghttp3-${NGHTTP3_VERSION} && \
    ./configure --prefix=/opt/curl-http3 --libdir=/opt/curl-http3/lib \
        --enable-lib-only --disable-static && \
    make -j"$(nproc)" && make install

RUN wget -q https://github.com/ngtcp2/ngtcp2/releases/download/v${NGTCP2_VERSION}/ngtcp2-${NGTCP2_VERSION}.tar.gz && \
    tar -xzf ngtcp2-${NGTCP2_VERSION}.tar.gz && \
    cd ngtcp2-${NGTCP2_VERSION} && \
    ./configure --prefix=/opt/curl-http3 --libdir=/opt/curl-http3/lib \
        --enable-lib-only --disable-static --with-gnutls --without-openssl && \
    make -j"$(nproc)" && make install

RUN wget -q https://curl.se/download/curl-${CURL_VERSION}.tar.gz && \
    tar -xzf curl-${CURL_VERSION}.tar.gz && \
    cd curl-${CURL_VERSION} && \
    ./configure --prefix=/opt/curl-http3 --libdir=/opt/curl-http3/lib \
        --disable-static --with-gnutls --without-openssl \
        --with-nghttp2 --with-nghttp3=/opt/curl-http3 --with-ngtcp2=/opt/curl-http3 \
        --with-zlib --without-libpsl --without-libidn2 \
        --without-brotli --without-zstd --without-libssh2 \
        --disable-ldap --disable-ldaps \
        --with-ca-bundle=/etc/ssl/certs/ca-certificates.crt \
        --with-ca-path=/etc/ssl/certs && \
    make -j"$(nproc)" && make install

FROM ghcr.io/eivarin/coreemu:9.2.1

RUN mkdir -p /build

# netcat is used in the core-gui script to check if the container is ready to accept connections on port 50051. We install it first before installing the rest of the packages to ensure that the readiness check works correctly.
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt update && \
    apt install -y --no-install-recommends netcat && \
    apt autoremove -y && \
    apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    libgnutls30 \
    libnghttp2-14 \
    zlib1g \
    wget \
    sudo \
    xterm \
    psmisc \
    python3 \
    python3-pip \
    python3-venv \
    python3-tk \
    iproute2 \
    iputils-ping \
    tcpdump \
    ethtool \
    procps \
    ffmpeg \
    nginx \
    unzip \
    && rm -rf /var/lib/apt/lists/*

# Uncomment the following lines if Firefox is needed in the container. 
# COPY install-firefox.sh /build/install-firefox.sh
# RUN sh /build/install-firefox.sh

# Install additional packages specified in packages.txt. The file should contain one package name per line, and lines starting with # will be treated as comments and ignored.
COPY packages.txt /build/packages.txt
RUN --mount=type=cache,target=/var/cache/apt,sharing=locked \
    --mount=type=cache,target=/var/lib/apt,sharing=locked \
    apt update && \
    grep -v '^#' /build/packages.txt | xargs apt install -y --no-install-recommends && \
    apt autoremove -y

COPY --from=curl-builder /opt/curl-http3 /opt/curl-http3
RUN ln -sf /opt/curl-http3/bin/curl /usr/local/bin/curl && \
    curl --version && \
    curl --version | grep -Eq '^Features:.* HTTP2( |$)' && \
    curl --version | grep -Eq '^Features:.* HTTP3( |$)'

COPY tp1-shell-init.sh /etc/tp1-shell-init.sh

RUN printf '\nsource /etc/tp1-shell-init.sh\n' >> /etc/bash.bashrc
