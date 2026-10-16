FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /srv
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && mkdir /data && chown 10001:10001 /data
COPY server.py config.py database.py schema*.sql ./
USER 10001:10001
EXPOSE 18081
HEALTHCHECK --interval=30s --timeout=8s --start-period=20s --retries=3 CMD python -c "import os, urllib.request; urllib.request.urlopen('http://127.0.0.1:' + os.environ.get('SMS_PORT', '18081') + '/health', timeout=5).read()"
CMD ["python", "server.py"]
