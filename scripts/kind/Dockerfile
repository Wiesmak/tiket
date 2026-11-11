FROM node:24.11.0-bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssl && rm -rf /var/lib/apt/lists/*
COPY issuer.mjs /issuer.mjs
USER 10001
ENTRYPOINT ["node","/issuer.mjs"]
