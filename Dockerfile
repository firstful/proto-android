# ── Build Stage ─────────────────────────────────────────────────
FROM docker.io/python:3.12-slim AS builder

WORKDIR /build

# Install build deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Install Python deps
COPY requirements.txt .
RUN pip install --no-cache-dir --trusted-host pypi.org --trusted-host files.pythonhosted.org -r requirements.txt

# Copy source
COPY server.py .
COPY src/ src/

# ── Runtime Stage ───────────────────────────────────────────────
FROM docker.io/python:3.12-slim

WORKDIR /app

# Runtime deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

# Copy from builder
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages
COPY --from=builder /usr/local/bin /usr/local/bin
COPY --from=builder /build/server.py .
COPY --from=builder /build/src/ src/

# Whisper model cache
VOLUME /app/cache

# Port
ENV HOST=0.0.0.0
ENV PORT=8321
EXPOSE 8321

# Healthcheck
HEALTHCHECK --interval=30s --timeout=15s --start-period=120s \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://localhost:8321/health')"

CMD ["python3", "server.py"]
