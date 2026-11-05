const http = require('http');
const fs = require('fs');
const path = require('path');

// Use port 3000 specifically because Nginx listens on 8080 and proxies to 3000
const PORT = parseInt(process.env.DEFAULT_APP_PORT || '3000', 10);
const HOST = '0.0.0.0';

const APK_PATHS = [
  path.join(__dirname, '.build-outputs', 'app-debug.apk'),
  path.join(__dirname, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk')
];

function getApkInfo() {
  for (const apkPath of APK_PATHS) {
    try {
      if (fs.existsSync(apkPath)) {
        const stats = fs.statSync(apkPath);
        return {
          exists: true,
          path: apkPath,
          sizeBytes: stats.size,
          sizeMb: (stats.size / (1024 * 1024)).toFixed(1),
          modified: stats.mtime.toISOString()
        };
      }
    } catch (_) {}
  }
  return {
    exists: false,
    path: null,
    sizeBytes: 0,
    sizeMb: '0',
    modified: null
  };
}

const HTML_CONTENT = `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Abuja Studio Goal Tracker | Xestlab Assistant</title>
  <meta name="description" content="Honest revenue and activity goal tracker for an Abuja studio targeting ₦10,000,000 across Nov 2026 to Oct 2027." />
  <meta property="og:title" content="Abuja Studio Goal Tracker" />
  <meta property="og:description" content="₦10M Goal Tracker & Android APK for Xestlab Assistant" />
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;600&display=swap" rel="stylesheet">
  <style>
    :root {
      --bg: #050507;
      --surface: #0E0E12;
      --surface-subtle: #17171F;
      --border: #272733;
      --accent: #7B2FF7;
      --accent-glow: rgba(123, 47, 247, 0.25);
      --accent-light: #9B5CFF;
      --text: #FFFFFF;
      --text-muted: #A0A0AC;
      --success: #10B981;
      --warning: #F59E0B;
    }
    * {
      box-sizing: border-box;
      margin: 0;
      padding: 0;
    }
    body {
      background-color: var(--bg);
      color: var(--text);
      font-family: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, sans-serif;
      line-height: 1.5;
      padding: 24px 16px 48px;
      min-height: 100vh;
      display: flex;
      flex-direction: column;
      align-items: center;
    }
    .wrapper {
      width: 100%;
      max-width: 820px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    header {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 16px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 12px;
      position: relative;
      overflow: hidden;
    }
    header::before {
      content: '';
      position: absolute;
      top: -60px;
      right: -60px;
      width: 180px;
      height: 180px;
      background: radial-gradient(circle, var(--accent-glow) 0%, transparent 70%);
      pointer-events: none;
    }
    .badge-row {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .tag {
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      padding: 4px 10px;
      border-radius: 999px;
      background: var(--surface-subtle);
      border: 1px solid var(--border);
      color: var(--text-muted);
    }
    .tag-accent {
      background: rgba(123, 47, 247, 0.15);
      border-color: var(--accent);
      color: var(--accent-light);
    }
    .tag-online {
      background: rgba(16, 185, 129, 0.15);
      border-color: rgba(16, 185, 129, 0.4);
      color: var(--success);
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .pulse-dot {
      width: 6px;
      height: 6px;
      border-radius: 50%;
      background: var(--success);
      box-shadow: 0 0 8px var(--success);
    }
    h1 {
      font-size: 26px;
      font-weight: 800;
      letter-spacing: -0.02em;
      color: var(--text);
    }
    .subtitle {
      color: var(--text-muted);
      font-size: 14px;
    }
    .hero-download-card {
      background: linear-gradient(135deg, rgba(123, 47, 247, 0.18) 0%, var(--surface) 100%);
      border: 1px solid var(--accent);
      border-radius: 16px;
      padding: 24px;
      display: flex;
      flex-direction: column;
      gap: 16px;
      box-shadow: 0 8px 32px var(--accent-glow);
    }
    .apk-title-row {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      flex-wrap: wrap;
    }
    .apk-title-row h2 {
      font-size: 18px;
      font-weight: 700;
      color: var(--text);
    }
    .apk-meta {
      font-family: 'JetBrains Mono', monospace;
      font-size: 12px;
      color: var(--text-muted);
      background: var(--surface-subtle);
      padding: 6px 12px;
      border-radius: 8px;
      border: 1px solid var(--border);
    }
    .btn-download {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      gap: 10px;
      background: var(--accent);
      color: #FFFFFF;
      font-weight: 700;
      font-size: 15px;
      padding: 14px 24px;
      border-radius: 10px;
      text-decoration: none;
      transition: all 0.2s ease;
      border: none;
      cursor: pointer;
    }
    .btn-download:hover {
      background: var(--accent-light);
      transform: translateY(-1px);
      box-shadow: 0 4px 20px var(--accent-glow);
    }
    .grid-2 {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
      gap: 16px;
    }
    .card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 14px;
    }
    .card-title {
      font-size: 12px;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--accent-light);
    }
    .stat-row {
      display: flex;
      justify-content: space-between;
      align-items: baseline;
      padding: 8px 0;
      border-bottom: 1px solid var(--border);
    }
    .stat-row:last-child {
      border-bottom: none;
    }
    .stat-label {
      color: var(--text-muted);
      font-size: 13px;
    }
    .stat-value {
      font-family: 'JetBrains Mono', monospace;
      font-weight: 600;
      color: var(--text);
      font-size: 14px;
    }
    .progress-bar-bg {
      width: 100%;
      height: 8px;
      background: var(--surface-subtle);
      border-radius: 999px;
      overflow: hidden;
      margin-top: 4px;
    }
    .progress-bar-fill {
      height: 100%;
      background: linear-gradient(90deg, var(--accent) 0%, var(--accent-light) 100%);
      border-radius: 999px;
      width: 15%;
    }
    .checklist {
      list-style: none;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .check-item {
      display: flex;
      align-items: center;
      gap: 12px;
      background: var(--surface-subtle);
      border: 1px solid var(--border);
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
    }
    .checkbox-box {
      width: 18px;
      height: 18px;
      border-radius: 4px;
      border: 2px solid var(--accent);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text);
      font-size: 11px;
      font-weight: 900;
      flex-shrink: 0;
    }
    .instructions-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 14px;
      padding: 20px;
    }
    .instructions-card h3 {
      font-size: 14px;
      font-weight: 700;
      color: var(--text);
      margin-bottom: 10px;
    }
    .instructions-card ol {
      padding-left: 20px;
      color: var(--text-muted);
      font-size: 13px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    footer {
      text-align: center;
      color: var(--text-muted);
      font-size: 12px;
      margin-top: 12px;
    }
  </style>
</head>
<body>
  <div class="wrapper">
    <header>
      <div class="badge-row">
        <span class="tag tag-online"><span class="pulse-dot"></span> DEV SERVER ONLINE :3000</span>
        <span class="tag tag-accent">KOTLIN / JETPACK COMPOSE</span>
        <span class="tag">AGP 9.1.1</span>
        <span class="tag">GRADLE 9.3.1</span>
      </div>
      <h1>Abuja Studio Goal Tracker</h1>
      <p class="subtitle">Personal AI Assistant & ₦10,000,000 Milestone Studio Engine (Nov 2026 – Oct 2027)</p>
    </header>

    <div class="hero-download-card">
      <div class="apk-title-row">
        <div>
          <h2>Download Android Application (APK)</h2>
          <p class="subtitle" style="font-size: 13px; margin-top: 4px;">
            Install directly on your Android phone or test in Android Studio / Genymotion emulator.
          </p>
        </div>
        <span class="apk-meta" id="apk-badge">CHECKING APK...</span>
      </div>
      <div style="display: flex; gap: 12px; flex-wrap: wrap; align-items: center;">
        <a href="/api/apk" class="btn-download" id="download-btn" download="AbujaStudioGoalTracker-debug.apk">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2 2v-4"></path>
            <polyline points="7 10 12 15 17 10"></polyline>
            <line x1="12" y1="15" x2="12" y2="3"></line>
          </svg>
          Download app-debug.apk
        </a>
        <button onclick="refreshStatus()" class="btn-download" style="background: var(--surface-subtle); border: 1px solid var(--border); color: var(--text-muted);">
          ↻ Refresh Status
        </button>
      </div>
    </div>

    <div class="grid-2">
      <div class="card">
        <span class="card-title">10 Million Annual Revenue Target</span>
        <div>
          <div style="display: flex; justify-content: space-between; align-items: baseline;">
            <span style="font-size: 24px; font-weight: 800; color: #FFFFFF;">₦10,000,000</span>
            <span class="stat-value" style="color: var(--accent-light);">Cycle: Nov '26 – Oct '27</span>
          </div>
          <div class="progress-bar-bg">
            <div class="progress-bar-fill"></div>
          </div>
        </div>
        <div class="stat-row">
          <span class="stat-label">Monthly Target</span>
          <span class="stat-value">₦833,333 / month</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Website Project Price</span>
          <span class="stat-value">₦250,000 (avg)</span>
        </div>
        <div class="stat-row">
          <span class="stat-label">Monthly Retainer Price</span>
          <span class="stat-value">₦150,000 / month</span>
        </div>
      </div>

      <div class="card">
        <span class="card-title">Core Daily Cadence (5 Steps)</span>
        <ul class="checklist">
          <li class="check-item"><span class="checkbox-box">✓</span> <span>07:30 Morning Plan Readout & Briefing</span></li>
          <li class="check-item"><span class="checkbox-box">✓</span> <span>Outreach to 5 New Business Leads</span></li>
          <li class="check-item"><span class="checkbox-box">✓</span> <span>Follow up with Day 2 & Day 5 Quotes</span></li>
          <li class="check-item"><span class="checkbox-box">✓</span> <span>Deliver Client Project Milestones</span></li>
          <li class="check-item"><span class="checkbox-box">✓</span> <span>Evening Cash & Pacing Log Review</span></li>
        </ul>
      </div>
    </div>

    <div class="instructions-card">
      <h3>How to install on Android phone:</h3>
      <ol>
        <li>Tap <strong>Download app-debug.apk</strong> on your Android phone browser.</li>
        <li>Open the downloaded APK and tap <strong>Install</strong> (allow "Install from Unknown Sources" if prompted).</li>
        <li>Launch <strong>Abuja Studio Goal Tracker</strong> to access the purple theme, speech AI coach, and cadence pipeline!</li>
      </ol>
    </div>

    <footer>
      Abuja Studio Goal Tracker • Powered by Android Jetpack Compose, ONNX Wake-Word & Groq/ElevenLabs AI
    </footer>
  </div>

  <script>
    async function refreshStatus() {
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        const badge = document.getElementById('apk-badge');
        if (data.apk && data.apk.exists) {
          badge.textContent = 'READY (' + data.apk.sizeMb + ' MB)';
          badge.style.color = '#10B981';
          badge.style.borderColor = 'rgba(16, 185, 129, 0.4)';
        } else {
          badge.textContent = 'BUILD IN PROGRESS';
          badge.style.color = '#F59E0B';
        }
      } catch (e) {
        console.error(e);
      }
    }
    refreshStatus();
  </script>
</body>
</html>`;

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, 'http://localhost');
  const pathname = parsedUrl.pathname;

  // Enable CORS
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, HEAD, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.statusCode = 204;
    return res.end();
  }

  // Health check endpoint
  if (pathname === '/health' || pathname === '/_health') {
    res.setHeader('Content-Type', 'application/json');
    res.statusCode = 200;
    return res.end(JSON.stringify({ status: 'ok', time: new Date().toISOString() }));
  }

  // Status API
  if (pathname === '/api/status') {
    res.setHeader('Content-Type', 'application/json');
    res.statusCode = 200;
    const apkInfo = getApkInfo();
    return res.end(JSON.stringify({
      app: 'Abuja Studio Goal Tracker',
      targetNgn: 10000000,
      framework: 'android',
      apk: apkInfo,
      timestamp: new Date().toISOString()
    }));
  }

  // Direct APK Download
  if (pathname === '/api/apk' || pathname === '/app-debug.apk' || pathname === '/download') {
    const apkInfo = getApkInfo();
    if (!apkInfo.exists) {
      res.statusCode = 404;
      res.setHeader('Content-Type', 'text/plain');
      return res.end('APK build in progress or not found. Please refresh in a moment.');
    }
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Length', apkInfo.sizeBytes);
    res.setHeader('Content-Disposition', 'attachment; filename="AbujaStudioGoalTracker-debug.apk"');
    const stream = fs.createReadStream(apkInfo.path);
    return stream.pipe(res);
  }

  // Root or any other path -> serve dashboard
  res.statusCode = 200;
  res.setHeader('Content-Type', 'text/html; charset=utf-8');
  return res.end(HTML_CONTENT);
});

server.listen(PORT, HOST, () => {
  console.log(`[Dev Server] Listening on http://${HOST}:${PORT}`);
});
