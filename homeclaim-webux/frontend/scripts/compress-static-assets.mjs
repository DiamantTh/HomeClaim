import { brotliCompressSync, brotliDecompressSync, constants as zlibConstants } from 'node:zlib';
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compress as zstdCompress, decompress as zstdDecompress, init as initZstd } from '@bokuweb/zstd-wasm';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const resourceRoot = path.resolve(scriptDir, '../../src/main/resources/static');
const outputRoot = path.resolve(scriptDir, '../../build/generated/compressed-resources');
const textExtensions = new Set(['.js', '.mjs', '.css', '.html', '.json', '.svg', '.xml', '.txt', '.map']);

await initZstd();
await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });

const results = [];
async function visit(directory) {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const source = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await visit(source);
      continue;
    }
    if (!entry.isFile() || !textExtensions.has(path.extname(entry.name).toLowerCase())) continue;

    const raw = await readFile(source);
    if (raw.length < 128) continue;
    const relative = path.relative(resourceRoot, source);
    const br = brotliCompressSync(raw, {
      params: {
        [zlibConstants.BROTLI_PARAM_QUALITY]: 10,
        [zlibConstants.BROTLI_PARAM_MODE]: zlibConstants.BROTLI_MODE_TEXT
      }
    });
    const zst = Buffer.from(zstdCompress(raw, 9));
    if (!brotliDecompressSync(br).equals(raw) || !Buffer.from(zstdDecompress(zst)).equals(raw)) {
      throw new Error(`Compression roundtrip failed for ${relative}`);
    }

    const outDirectory = path.join(outputRoot, 'static', path.dirname(relative));
    await mkdir(outDirectory, { recursive: true });
    if (br.length < raw.length) await writeFile(path.join(outDirectory, `${path.basename(relative)}.br`), br);
    if (zst.length < raw.length) await writeFile(path.join(outDirectory, `${path.basename(relative)}.zst`), zst);
    const sha256 = createHash('sha256').update(raw).digest('hex').slice(0, 12);
    results.push({ relative, sha256, raw: raw.length, br: br.length, zst: zst.length });
  }
}

await visit(resourceRoot);
for (const result of results.sort((a, b) => a.relative.localeCompare(b.relative))) {
  console.log(`${result.relative} ${result.sha256} raw=${result.raw} br=${result.br} zstd=${result.zst}`);
}
console.log(`Precompressed ${results.length} text assets (Brotli quality 10, Zstandard level 9); only smaller sidecars were emitted.`);
