FROM python:3.12-slim AS base

ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=600 \
    PIP_NO_CACHE_DIR=1 \
    PIP_PROGRESS_BAR=off \
    PIP_RETRIES=10 \
    MKL_NUM_THREADS=1 \
    NUMEXPR_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    OMP_NUM_THREADS=1 \
    PYTHONPATH=/app/src \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt requirements-test.txt ./

FROM base AS test
RUN pip install --index-url "${PIP_INDEX_URL}" --requirement requirements-test.txt
COPY pytest.ini ./pytest.ini
COPY src ./src
COPY tests ./tests
CMD ["pytest", "-q"]

FROM base AS runtime
RUN pip install --index-url "${PIP_INDEX_URL}" --requirement requirements.txt
COPY src ./src
RUN mkdir -p /cache
EXPOSE 18090
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:18090/health', timeout=3)"
CMD ["uvicorn", "risk_gateway.app:app", "--host", "0.0.0.0", "--port", "18090"]
