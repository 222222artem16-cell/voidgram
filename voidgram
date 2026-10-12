FROM node:22-slim
WORKDIR /app
COPY server/package.json ./
RUN npm install --omit=dev
COPY server/server.js ./
ENV PORT=8080 DATA_DIR=/data
RUN mkdir -p /data
EXPOSE 8080
CMD ["node", "--no-warnings", "server.js"]
