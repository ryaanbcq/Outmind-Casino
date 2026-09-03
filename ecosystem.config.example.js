// pm2 process file. Copy to ecosystem.config.js (gitignored) and adjust paths.
// Secrets stay in the .env files: mineflayer-bot/.env is loaded by bot.js and
// bridge.js themselves, discord-bot/.env is read here and passed as env.
const fs = require('fs');
const path = require('path');

function readEnvFile(p) {
  const o = {};
  try {
    for (const line of fs.readFileSync(p, 'utf8').split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
      if (m) o[m[1]] = m[2];
    }
  } catch (e) { /* no file yet */ }
  return o;
}

const ROOT = __dirname;
const discordEnv = readEnvFile(path.join(ROOT, 'discord-bot', '.env'));

module.exports = {
  apps: [
    {
      name: 'mineflayer-bot',
      cwd: path.join(ROOT, 'mineflayer-bot'),
      script: 'bot.js',
      node_args: '--max-old-space-size=384',
      max_memory_restart: '700M',
      max_restarts: 100,
      restart_delay: 10000,
      out_file: path.join(ROOT, 'logs', 'mineflayer-out.log'),
      error_file: path.join(ROOT, 'logs', 'mineflayer-err.log'),
    },
    {
      name: 'bridge',
      cwd: path.join(ROOT, 'mineflayer-bot'),
      script: 'bridge.js',
      max_memory_restart: '300M',
      max_restarts: 100,
      restart_delay: 10000,
      out_file: path.join(ROOT, 'logs', 'bridge-out.log'),
      error_file: path.join(ROOT, 'logs', 'bridge-err.log'),
    },
    {
      name: 'discord-bot',
      cwd: path.join(ROOT, 'discord-bot'),
      script: 'bot.js',
      max_memory_restart: '400M',
      max_restarts: 100,
      restart_delay: 10000,
      out_file: path.join(ROOT, 'logs', 'discord-out.log'),
      error_file: path.join(ROOT, 'logs', 'discord-err.log'),
      env: {
        BOT_DIR: path.join(ROOT, 'mineflayer-bot'),
        DISCORD_BOT_DIR: path.join(ROOT, 'discord-bot'),
        FONTS_DIR: path.join(ROOT, 'fonts'),
        ...discordEnv,
      },
    },
  ],
};
