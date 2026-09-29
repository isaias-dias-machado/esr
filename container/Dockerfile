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
    curl \
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

COPY tp1-shell-init.sh /etc/tp1-shell-init.sh

RUN printf '\nsource /etc/tp1-shell-init.sh\n' >> /etc/bash.bashrc
