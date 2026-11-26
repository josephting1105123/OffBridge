#!/usr/bin/env node
/**
 * Builds the single licence document both clients show under Settings -> Open-source licences.
 *
 *   node tools/build-oss-licences.mjs
 *
 * Source of truth is the repo root: LICENSE, NOTICE and licenses/*.txt. This only concatenates
 * them, so correcting a licence there is enough — re-run this and rebuild. Output is committed
 * (not generated at build time) so the exact text shipped to users is visible in review.
 *
 * The document is deliberately NOT translated. Licence texts are legal instruments; a localised
 * paraphrase would not be the licence, and every one of these requires the text itself.
 *
 * Dependencies only the Windows build ships are appended to its copy alone (section 6 on).
 *
 * Writes:
 *   app/src/main/res/raw/oss_licences.txt          (Android, read via resources.openRawResource)
 *   Window Ver/src/main/resources/oss_licences.txt (Windows, read via the classloader)
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const read = (p) => readFile(path.join(ROOT, p), 'utf8');

const rule = (title) =>
  `${'='.repeat(78)}\n${title}\n${'='.repeat(78)}\n\n`;

// Apache-2.0 dependencies. Their licence is the same text already reproduced in full below, so
// listing them here plus that one copy is what Apache-2.0 s4(1) asks for. None of them ships a
// NOTICE file of its own that would need propagating under s4(4).
const APACHE_DEPS = [
  'AndroidX (core, appcompat, activity, constraintlayout, camera, security-crypto)',
  'Jetpack Compose and Compose Multiplatform',
  'AndroidX Room',
  'Kotlin standard library and kotlinx.coroutines',
  'ZXing (barcode decoding)',
  'Gson',
  'Java Native Access (JNA) — dual-licensed, taken under the Apache-2.0 option',
  'Google Android Material Components',
];

// JmDNS's NOTICE.txt, which Apache-2.0 s4(d) asks redistributors to pass on.
const JMDNS_NOTICE = [
  'Java Multicast Domain Name Server (JmDNS)',
  '',
  'This project was originally developed by Arthur van Hoff under the GNU',
  'Lesser General Public License as jRendevous.  It was moved to Sourceforge',
  'by Rick Blair and renamed to JmDNS with the Arthur\'s kind permission.',
  'It has been re-released under the Apache License, Version 2.0.',
  'In 2014, it has been moved from Sourceforge to Github by Kai Kreuzer',
  'with the kind approval from Arthur and Rick.',
  '',
  'Details of the Apache License, Version 2.0 can be found at:',
  'http://www.apache.org/licenses/',
  '',
  'For other details please see the README.txt file.',
].join('\n');

async function main() {
  const [license, notice, ofl, shimmer, protobuf, slf4j] = await Promise.all([
    read('LICENSE'),
    read('NOTICE'),
    read('licenses/OFL-1.1.txt'),
    read('licenses/BSD-2-Clause-shimmer.txt'),
    read('licenses/BSD-3-Clause-protobuf.txt'),
    read('licenses/MIT-slf4j.txt'),
  ]);

  const doc = [
    rule('OFFBRIDGE — OPEN-SOURCE LICENCES'),
    'This screen lists every third-party component redistributed inside this build,\n' +
    'with the full text of each licence that requires it. Generated from the project\n' +
    'repository by tools/build-oss-licences.mjs.\n\n\n',

    rule('1. OFFBRIDGE ITSELF'),
    notice.trimEnd() + '\n\n\n',

    rule('2. APACHE LICENSE 2.0'),
    'OffBridge and the dependencies listed here are all licensed under the Apache\n' +
    'License, Version 2.0, reproduced in full below.\n\n' +
    APACHE_DEPS.map((d) => `  - ${d}`).join('\n') + '\n\n' +
    'Google ML Kit Barcode Scanning is NOT open source. It is a proprietary Google\n' +
    'SDK used under the Google APIs Terms of Service, redistributable as part of an\n' +
    'application. No source or licence text is available to reproduce here.\n\n' +
    license.trimEnd() + '\n\n\n',

    rule('3. SIL OPEN FONT LICENSE 1.1 — IBM Plex Sans'),
    'The bundled font is IBM Plex Sans. The OFL requires this text to travel with it.\n\n' +
    ofl.trimEnd() + '\n\n\n',

    rule('4. BSD 2-CLAUSE — Facebook Shimmer for Android'),
    shimmer.trimEnd() + '\n\n\n',

    rule('5. BUNDLED JAVA RUNTIME — Windows build only'),
    'The Windows installer ships a Java runtime built from Eclipse Temurin 17\n' +
    '(OpenJDK), licensed under GPLv2 with the Classpath Exception. The Classpath\n' +
    'Exception is what allows it to be distributed alongside this application without\n' +
    'the GPL extending to it.\n\n' +
    'Its full licence texts are installed with the application, under:\n\n' +
    '    <install folder>\\runtime\\legal\\\n\n' +
    'This section does not apply to the Android build, which bundles no Java runtime.\n',
  ].join('');

  const windowsOnly = [
    '\n\n',
    rule('6. BSD 3-CLAUSE — Protocol Buffers and Chromium — Windows build only'),
    protobuf.trimEnd() + '\n\n\n',

    'The securemessage and UKEY2 message definitions follow the Google ukey2 project\n' +
    '(github.com/google/ukey2, Copyright 2020 Google LLC), used under the Apache\n' +
    'License, Version 2.0, reproduced in section 2.\n\n\n',

    rule('7. JmDNS — Apache License 2.0 — Windows build only'),
    'JmDNS is used under the Apache License, Version 2.0, reproduced in section 2.\n' +
    'Its NOTICE file reads:\n\n' +
    JMDNS_NOTICE + '\n\n',

    rule('8. MIT — SLF4J — Windows build only'),
    slf4j.trimEnd() + '\n',
  ].join('');

  const targets = [
    ['app/src/main/res/raw/oss_licences.txt', doc],
    ['Window Ver/src/main/resources/oss_licences.txt', doc + windowsOnly],
  ];
  for (const [t, text] of targets) {
    const abs = path.join(ROOT, t);
    await mkdir(path.dirname(abs), { recursive: true });
    await writeFile(abs, text, 'utf8');
    console.log(`  ${t}  ${(text.length / 1024).toFixed(1)}KB`);
  }
}

await main();
