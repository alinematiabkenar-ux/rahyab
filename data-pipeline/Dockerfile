# RahYab Offline State Map Pack Builder
# Base image includes prebuilt Valhalla binaries (valhalla_build_tiles, valhalla_build_config)
FROM ghcr.io/gis-ops/docker-valhalla/valhalla:latest

USER root

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PMTILES_VERSION=1.22.1

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    sqlite3 \
    curl \
    ca-certificates \
    osmium-tool \
    libosmium2-dev \
    && rm -rf /var/lib/apt/lists/*

# Install go-pmtiles CLI for extracting per-state PMTiles from Protomaps basemap
RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "arm64" ]; then PM_ARCH="Linux_arm64"; else PM_ARCH="Linux_x86_64"; fi && \
    curl -fSL "https://github.com/protomaps/go-pmtiles/releases/download/v${PMTILES_VERSION}/go-pmtiles_${PMTILES_VERSION}_${PM_ARCH}.tar.gz" \
    | tar -xz -C /usr/local/bin pmtiles && \
    chmod +x /usr/local/bin/pmtiles

WORKDIR /pipeline

COPY requirements.txt /pipeline/requirements.txt
RUN python3 -m pip install --no-cache-dir --break-system-packages -r /pipeline/requirements.txt

COPY *.py /pipeline/

ENTRYPOINT ["python3", "/pipeline/build_state_pack.py"]
