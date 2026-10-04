FROM ghcr.io/astral-sh/uv:0.12.23 AS uv-bin

FROM python:3.12-slim-trixie

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    PATH=/opt/venv/bin:$PATH \
    TVCOMPILER_HOST=0.0.0.0 \
    TVCOMPILER_PORT=8000 \
    TVCOMPILER_INSTANCE_DIR=/data/instance \
    HOME=/data/home

COPY --from=uv-bin /uv /uvx /bin/

RUN apt-get update \
    && apt-get install -y --no-install-recommends adb ca-certificates tzdata \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 tvcompiler \
    && useradd --uid 10001 --gid tvcompiler --home-dir /data/home --no-create-home --shell /usr/sbin/nologin tvcompiler \
    && mkdir -p /data/instance /data/home/.android \
    && chown -R tvcompiler:tvcompiler /data

WORKDIR /app
COPY pyproject.toml uv.lock README.md LICENSE ./
COPY src ./src
RUN uv sync --locked --no-dev \
    && python -c "import tvcompiler, tvcompiler.web; from zoneinfo import ZoneInfo; ZoneInfo('America/Chicago')" \
    && adb help 2>&1 | grep -E '(^|[[:space:]])(pair|mdns)([[:space:]]|$)'

USER tvcompiler:tvcompiler
VOLUME ["/data"]
EXPOSE 8000
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)"]
ENTRYPOINT ["tvcompiler"]
