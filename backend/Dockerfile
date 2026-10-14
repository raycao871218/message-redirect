FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 SMS_CONFIG_FILE=/run/secrets/server.json
WORKDIR /srv
COPY requirements.txt ./
COPY wheels /wheels
RUN pip install --no-cache-dir --no-index --find-links=/wheels -r requirements.txt && rm -rf /wheels
COPY server.py schema.sql ./
USER 1026:100
EXPOSE 18081
CMD ["python", "server.py"]
