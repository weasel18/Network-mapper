FROM python:3.12-slim
WORKDIR /app
COPY server.py .
COPY web ./web
RUN useradd --system --uid 10001 --no-create-home mapsync && mkdir -p /data && chown mapsync /data
USER mapsync
ENV DATA_DIR=/data PORT=8080 PYTHONUNBUFFERED=1
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request,os,ssl;s='https' if os.environ.get('TLS_CERT') else 'http';urllib.request.urlopen(s+'://127.0.0.1:'+os.environ.get('PORT','8080')+'/api/health',context=ssl._create_unverified_context())"
CMD ["python", "server.py"]
