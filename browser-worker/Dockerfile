FROM mcr.microsoft.com/playwright:v1.50.1-noble

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .

ENV PORT=3001
ENV HEADLESS=true

EXPOSE 3001

CMD ["node", "src/server.js"]
