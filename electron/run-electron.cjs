const { spawn } = require('node:child_process');
const electronPath = require('electron');

const env = { ...process.env };
delete env.ELECTRON_RUN_AS_NODE;

if (process.env.VITE_DEV_SERVER_URL) {
  env.VITE_DEV_SERVER_URL = process.env.VITE_DEV_SERVER_URL;
}

const child = spawn(electronPath, ['.'], {
  env,
  stdio: 'inherit',
  windowsHide: false,
});

child.on('exit', (code) => {
  process.exit(code ?? 0);
});
