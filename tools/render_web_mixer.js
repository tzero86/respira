// Render the landing page's web mixer layers to WAV files so they can be
// measured with the same metrics as the app's layers.
//
// Usage:
//   node tools/render_web_mixer.js
//
// It reads the synthesis out of docs/assets/js/site.js so the measurement
// always matches what the page actually does.

const fs = require("fs");
const path = require("path");
const vm = require("vm");

const ROOT = path.dirname(__dirname);
const SITE = path.join(ROOT, "docs", "assets", "js", "site.js");
const OUT = path.join(ROOT, "tools", "out", "webmixer");

// A minimal stand-in for the Web Audio API: enough for buildLayer.
const SR = 44100;

function makeCtx() {
  return {
    sampleRate: SR,
    createBuffer(channels, length) {
      const data = new Float32Array(length);
      return {
        getChannelData: () => data,
        length,
      };
    },
  };
}

// Pull buildLayer out of the site script without running the whole page.
function extractBuildLayer(source) {
  const start = source.indexOf("function buildLayer(ctx, kind) {");
  if (start < 0) throw new Error("buildLayer not found");
  let depth = 0;
  let i = source.indexOf("{", start);
  const from = i;
  for (; i < source.length; i += 1) {
    if (source[i] === "{") depth += 1;
    else if (source[i] === "}") {
      depth -= 1;
      if (depth === 0) break;
    }
  }
  return source.slice(start, i + 1);
}

const source = fs.readFileSync(SITE, "utf8");
const code = extractBuildLayer(source);
const sandbox = { Math, console };
vm.createContext(sandbox);
vm.runInContext(`${code}\nthis.buildLayer = buildLayer;`, sandbox);
const buildLayer = sandbox.buildLayer;

fs.mkdirSync(OUT, { recursive: true });

const LAYERS = ["rain", "thunder", "wind", "stream", "ocean", "fire"];

function writeWav(path, samples, sr) {
  const bytes = samples.length * 2;
  const buffer = Buffer.alloc(44 + bytes);
  buffer.write("RIFF", 0);
  buffer.writeUInt32LE(36 + bytes, 4);
  buffer.write("WAVE", 8);
  buffer.write("fmt ", 12);
  buffer.writeUInt32LE(16, 16);
  buffer.writeUInt16LE(1, 20);
  buffer.writeUInt16LE(1, 22);
  buffer.writeUInt32LE(sr, 24);
  buffer.writeUInt32LE(sr * 2, 28);
  buffer.writeUInt16LE(2, 32);
  buffer.writeUInt16LE(16, 34);
  buffer.write("data", 36);
  buffer.writeUInt32LE(bytes, 40);
  for (let i = 0; i < samples.length; i += 1) {
    const v = Math.max(-1, Math.min(1, samples[i]));
    buffer.writeInt16LE(Math.round(v * 32767), 44 + i * 2);
  }
  fs.writeFileSync(path, buffer);
}

for (const layer of LAYERS) {
  const ctx = makeCtx();
  const buf = buildLayer(ctx, layer);
  const data = buf.getChannelData(0);
  // the page loops a four second buffer; render eight seconds so the
  // measurement sees the loop as the listener does
  const out = new Float32Array(data.length * 2);
  out.set(data, 0);
  out.set(data, data.length);
  const file = path.join(OUT, `${layer}.wav`);
  writeWav(file, out, SR);
  console.log(`${layer.padEnd(9)} ${(out.length / SR).toFixed(1)}s -> ${path.relative(ROOT, file)}`);
}
