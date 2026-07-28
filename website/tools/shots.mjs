#!/usr/bin/env node
/**
 * Screenshot encoder for the website + showcase listings.
 *
 * Drop raw captures into  website/assets/screenshots/_raw/
 * named exactly like their target (e.g. android-clipboard-dark.png), then run:
 *
 *   node tools/shots.mjs          encode every raw capture
 *   node tools/shots.mjs --check  report which expected shots are missing
 *
 * Platform rules are picked from the filename prefix. Android captures are
 * cropped to the 9:19.5 the CSS phone bezel expects before downscaling.
 */
import sharp from 'sharp';
import { readdir, mkdir, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SHOTS = path.join(HERE, '..', 'assets', 'screenshots');
const RAW = path.join(SHOTS, '_raw');

// Served dimensions. Android matches the 9:19.5 of .device--phone in css/device.css;
// Windows matches the landscape .device--desktop card.
const RULES = {
  android: { width: 800, ratio: 9 / 19.5, quality: 82 },
  windows: { width: 1200, quality: 82 },
};

// Wired into index.html today — a missing file here shows the CSS
// "Screenshot pending" placeholder on the live site.
const IN_USE = [
  'android-clipboard', 'android-sas-verify', 'android-transfer',
  'windows-main', 'windows-transfer',
];
// Captured for the showcase repo / future sections. Not referenced by
// index.html, so absence costs nothing on the site.
const SPARE = [
  'android-pairing-qr', 'android-history', 'android-send-sheet',
  'android-nfc', 'android-scanner', 'android-home', 'windows-settings',
];

function ruleFor(name) {
  const platform = name.split('-')[0];
  const rule = RULES[platform];
  if (!rule) throw new Error(`${name}: filename must start with "android-" or "windows-"`);
  return rule;
}

async function encode(file) {
  const name = path.basename(file, path.extname(file));
  const rule = ruleFor(name);
  const src = sharp(path.join(RAW, file));
  const meta = await src.metadata();
  let pipe = src;

  // Android: trim the gesture bar off the bottom so the frame lands on 9:19.5
  // exactly. The CSS bezel masks to that ratio; anything else gets letterboxed.
  if (rule.ratio) {
    const target = Math.round(meta.width / rule.ratio);
    if (target > meta.height) {
      throw new Error(
        `${file}: ${meta.width}x${meta.height} is shorter than the 9:19.5 crop needs ` +
        `(${meta.width}x${target}). Capture at full device resolution.`
      );
    }
    if (target < meta.height) {
      pipe = pipe.extract({ left: 0, top: 0, width: meta.width, height: target });
    }
  }

  const out = path.join(SHOTS, `${name}.webp`);
  const info = await pipe
    .resize({ width: rule.width })
    .webp({ quality: rule.quality })
    .toFile(out);

  const trimmed = rule.ratio ? ` (cropped from ${meta.height}px tall)` : '';
  console.log(
    `  ${name}.webp  ${info.width}x${info.height}  ${Math.round(info.size / 1024)}KB${trimmed}`
  );
  return `${name}.webp`;
}

async function check() {
  const report = (label, names) => {
    console.log(`\n${label}`);
    for (const base of names) {
      for (const theme of ['dark', 'light']) {
        const f = path.join(SHOTS, `${base}-${theme}.webp`);
        console.log(`  ${existsSync(f) ? 'ok     ' : 'MISSING'} ${base}-${theme}.webp`);
      }
    }
  };
  report('Wired into index.html — missing means a placeholder ships:', IN_USE);
  report('Spare / showcase-only — safe to skip:', SPARE);
}

if (process.argv.includes('--check')) {
  await check();
} else {
  if (!existsSync(RAW)) {
    await mkdir(RAW, { recursive: true });
    console.log(`Created ${path.relative(process.cwd(), RAW)} — drop raw captures there and re-run.`);
    process.exit(0);
  }
  const files = (await readdir(RAW)).filter(f => /\.(png|jpe?g)$/i.test(f));
  if (!files.length) {
    console.log('No .png/.jpg captures found in _raw/.');
    process.exit(0);
  }
  console.log(`Encoding ${files.length} capture(s):`);
  let failed = 0;
  for (const f of files.sort()) {
    try {
      await encode(f);
    } catch (err) {
      failed++;
      console.error(`  FAILED ${f}: ${err.message}`);
    }
  }
  if (failed) process.exitCode = 1;
}
