const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8081;

const server = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', '*');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  const cleanUrl = req.url.split('?')[0];

  // 429 Rate Limit fixture: return HTTP 429 with Retry-After header
  if (cleanUrl === '/rate-limit-429' || cleanUrl === '/rate-limit-429.html') {
    const filePath = path.join(__dirname, 'rate-limit-429.html');
    fs.readFile(filePath, (err, data) => {
      res.writeHead(429, {
        'Content-Type': 'text/html; charset=utf-8',
        'Retry-After': '5',
      });
      res.end(err ? '429 Too Many Requests' : data);
    });
    return;
  }

  // Cloudflare block fixture: return HTTP 403 Forbidden
  if (cleanUrl === '/cloudflare-block' || cleanUrl === '/cloudflare-block.html') {
    const filePath = path.join(__dirname, 'cloudflare-block.html');
    fs.readFile(filePath, (err, data) => {
      res.writeHead(403, {
        'Content-Type': 'text/html; charset=utf-8',
        'cf-ray': '8e5f29a0b12c34d5',
      });
      res.end(err ? '403 Forbidden' : data);
    });
    return;
  }

  let fileName = 'index.html';
  if (cleanUrl === '/normal' || cleanUrl === '/normal.html') {
    fileName = 'normal.html';
  } else if (cleanUrl === '/captcha-visible' || cleanUrl === '/captcha-visible.html') {
    fileName = 'captcha-visible.html';
  } else if (cleanUrl === '/invisible-badge' || cleanUrl === '/invisible-badge.html') {
    fileName = 'invisible-badge.html';
  } else if (cleanUrl === '/signin-landing' || cleanUrl === '/signin-landing.html') {
    fileName = 'signin-landing.html';
  } else if (cleanUrl === '/signin-modal' || cleanUrl === '/signin-modal.html') {
    fileName = 'signin-modal.html';
  } else if (cleanUrl !== '/' && fs.existsSync(path.join(__dirname, cleanUrl.replace(/^\//, '')))) {
    fileName = cleanUrl.replace(/^\//, '');
  }

  const filePath = path.join(__dirname, fileName);
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('Not Found');
      return;
    }
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end(data);
  });
});

if (require.main === module) {
  server.listen(PORT, () => {
    console.log(`[DOO Fixture] Serving test fixture on http://localhost:${PORT}`);
  });
}

module.exports = server;
