# Imagen lista para desplegar. En modo Real-Debrid el servidor no transcodifica
# nada (el vídeo va del CDN de RD al navegador), así que no hace falta ffmpeg.
FROM node:20-slim

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev

COPY . .

ENV PORT=3000
EXPOSE 3000

CMD ["node", "server.js"]
