# Imagen lista para desplegar (incluye ffmpeg para transcodificar/subtítulos)
FROM node:20-slim

# ffmpeg para transcodificación en vivo y extracción de subtítulos embebidos
RUN apt-get update \
  && apt-get install -y --no-install-recommends ffmpeg \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

ENV PORT=3000
EXPOSE 3000

CMD ["node", "server.js"]
