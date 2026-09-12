const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const rootDir = path.resolve(__dirname, '..');
const packagePath = path.join(rootDir, 'package.json');
const packageJson = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
const productName = packageJson.build?.productName || 'GI POS Restaurant';

const args = parseArgs(process.argv.slice(2));
const version = String(args.version || packageJson.version || '').trim();
const tagName = normalizeTag(args.tag || version);
const releaseDir = path.join(rootDir, 'release');
const title = String(args.title || `${productName} ${version}`);
const notes = readReleaseNotes(args);
const dryRun = Boolean(args['dry-run']);
const allowDirty = Boolean(args['allow-dirty']);
const draft = Boolean(args.draft);
const prerelease = Boolean(args.prerelease);
const ghCommand = resolveCommand('gh', [
  path.join(process.env.ProgramFiles || 'C:\\Program Files', 'GitHub CLI', 'gh.exe'),
  path.join(process.env.LOCALAPPDATA || '', 'Programs', 'GitHub CLI', 'gh.exe'),
]);

main();

function main() {
  if (!version) {
    fail('Version is missing. Set package.json version or pass --version 1.2.3.');
  }

  if (!notes.trim()) {
    fail('Release notes are required. Pass --notes "Short description" or --notes-file RELEASE_NOTES.md.');
  }

  const assets = getReleaseAssets(version);
  validateReleaseAssets(version, assets);
  ensureGitHubCli();
  ensureCleanWorktree();

  const notesFile = writeTempNotesFile(version, notes);

  try {
    log(`Version: ${version}`);
    log(`Tag: ${tagName}`);
    log(`Title: ${title}`);
    log('Assets:');
    assets.forEach((asset) => log(`  - ${path.relative(rootDir, asset)}`));

    if (dryRun) {
      log('Dry run complete. No GitHub release was created.');
      return;
    }

    ensureGitTag(tagName, title);
    pushGitTag(tagName);
    upsertGitHubRelease(tagName, title, notesFile, assets);
    log(`GitHub release archive ready: ${tagName}`);
  } finally {
    fs.rmSync(notesFile, { force: true });
  }
}

function parseArgs(argv) {
  const parsed = {};

  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];

    if (!token.startsWith('--')) {
      continue;
    }

    const rawKey = token.slice(2);
    const equalsIndex = rawKey.indexOf('=');

    if (equalsIndex !== -1) {
      parsed[rawKey.slice(0, equalsIndex)] = rawKey.slice(equalsIndex + 1);
      continue;
    }

    const next = argv[index + 1];
    if (next && !next.startsWith('--')) {
      parsed[rawKey] = next;
      index += 1;
    } else {
      parsed[rawKey] = true;
    }
  }

  return parsed;
}

function readReleaseNotes(parsedArgs) {
  if (parsedArgs['notes-file']) {
    const notesPath = path.resolve(rootDir, String(parsedArgs['notes-file']));
    if (!fs.existsSync(notesPath)) {
      fail(`Release notes file not found: ${notesPath}`);
    }
    return fs.readFileSync(notesPath, 'utf8');
  }

  return String(parsedArgs.notes || process.env.GI_RELEASE_NOTES || '').trim();
}

function normalizeTag(value) {
  const text = String(value || '').trim();
  return text.startsWith('v') ? text : `v${text}`;
}

function getReleaseAssets(releaseVersion) {
  const setupExe = path.join(releaseDir, `${productName} Setup ${releaseVersion}.exe`);
  const setupBlockmap = `${setupExe}.blockmap`;
  const latestYml = path.join(releaseDir, 'latest.yml');
  const portableExe = path.join(releaseDir, `${productName} Portable ${releaseVersion}.exe`);

  return [latestYml, setupExe, setupBlockmap, portableExe].filter((asset) => fs.existsSync(asset));
}

function validateReleaseAssets(releaseVersion, assets) {
  const latestYml = path.join(releaseDir, 'latest.yml');
  const setupExe = path.join(releaseDir, `${productName} Setup ${releaseVersion}.exe`);
  const setupBlockmap = `${setupExe}.blockmap`;
  const required = [latestYml, setupExe, setupBlockmap];
  const missing = required.filter((asset) => !fs.existsSync(asset));

  if (missing.length) {
    fail(
      [
        `Missing release artifact(s) for ${releaseVersion}:`,
        ...missing.map((asset) => `  - ${path.relative(rootDir, asset)}`),
        'Run npm run dist:win first.',
      ].join('\n'),
    );
  }

  const manifest = fs.readFileSync(latestYml, 'utf8');
  const setupName = path.basename(setupExe);

  if (!manifest.includes(`version: ${releaseVersion}`)) {
    fail(`release/latest.yml does not contain version: ${releaseVersion}`);
  }

  if (!manifest.includes(setupName)) {
    fail(`release/latest.yml does not point to ${setupName}`);
  }

  if (!assets.length) {
    fail('No release assets found.');
  }
}

function ensureGitHubCli() {
  run(ghCommand, ['--version'], { capture: true, errorMessage: 'GitHub CLI is required. Install gh and run gh auth login.' });
  if (dryRun) {
    return;
  }
  run(ghCommand, ['auth', 'status'], { capture: true, errorMessage: 'GitHub CLI is not authenticated. Run gh auth login.' });
}

function ensureCleanWorktree() {
  if (allowDirty) {
    return;
  }

  const status = run('git', ['status', '--porcelain'], { capture: true }).trim();
  if (status) {
    fail('Git worktree has uncommitted changes. Commit first, or pass --allow-dirty if this is intentional.');
  }
}

function writeTempNotesFile(releaseVersion, releaseNotes) {
  const filePath = path.join(os.tmpdir(), `gi-pos-release-${releaseVersion}-${Date.now()}.md`);
  fs.writeFileSync(filePath, releaseNotes.trim() + '\n', 'utf8');
  return filePath;
}

function ensureGitTag(tag, message) {
  const localExists = commandSucceeds('git', ['rev-parse', '--verify', tag]);
  if (!localExists) {
    run('git', ['tag', '-a', tag, '-m', message]);
  }
}

function pushGitTag(tag) {
  run('git', ['push', 'origin', tag]);
}

function upsertGitHubRelease(tag, releaseTitle, notesFile, assets) {
  const releaseExists = commandSucceeds(ghCommand, ['release', 'view', tag]);
  const assetArgs = assets.map((asset) => path.relative(rootDir, asset));

  if (releaseExists) {
    run(ghCommand, ['release', 'edit', tag, '--title', releaseTitle, '--notes-file', notesFile]);
    run(ghCommand, ['release', 'upload', tag, ...assetArgs, '--clobber']);
    return;
  }

  const createArgs = ['release', 'create', tag, ...assetArgs, '--title', releaseTitle, '--notes-file', notesFile];

  if (draft) {
    createArgs.push('--draft');
  }

  if (prerelease) {
    createArgs.push('--prerelease');
  }

  run(ghCommand, createArgs);
}

function resolveCommand(command, fallbackPaths = []) {
  if (commandSucceeds(command, ['--version'])) {
    return command;
  }

  const fallback = fallbackPaths.find((candidate) => candidate && fs.existsSync(candidate));
  return fallback || command;
}

function commandSucceeds(command, commandArgs) {
  try {
    execFileSync(command, commandArgs, { cwd: rootDir, stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

function run(command, commandArgs, options = {}) {
  if (dryRun && command !== 'git' && command !== ghCommand) {
    return '';
  }

  try {
    const stdio = options.capture ? 'pipe' : 'inherit';
    const output = execFileSync(command, commandArgs, { cwd: rootDir, encoding: 'utf8', stdio });
    return output || '';
  } catch (error) {
    if (options.errorMessage) {
      fail(options.errorMessage);
    }
    const detail = error?.stderr ? String(error.stderr) : error?.message || 'Command failed';
    fail(`${command} ${commandArgs.join(' ')} failed.\n${detail}`);
  }
}

function log(message) {
  process.stdout.write(`${message}\n`);
}

function fail(message) {
  process.stderr.write(`${message}\n`);
  process.exit(1);
}
