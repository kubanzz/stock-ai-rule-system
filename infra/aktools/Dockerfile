FROM python:3.12-slim

ARG PIP_INDEX_URL=https://pypi.org/simple

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_DEFAULT_TIMEOUT=600 \
    PIP_NO_CACHE_DIR=1 \
    PIP_PROGRESS_BAR=off \
    PIP_RETRIES=10 \
    PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt ./requirements.txt
RUN pip install --index-url "${PIP_INDEX_URL}" setuptools wheel \
    && pip install --index-url "${PIP_INDEX_URL}" --no-build-isolation --requirement requirements.txt

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/version', timeout=3)"

CMD ["python", "-m", "aktools", "--host", "0.0.0.0", "--port", "8090"]
