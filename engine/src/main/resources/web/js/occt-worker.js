// Reads a STEP file with OpenCascade (occt-import-js, WebAssembly) for cadconvert.js, and always
// answers, { ok: true, result } or { ok: false, error }: an exception or a WebAssembly abort (out of
// memory, a file it can't parse) comes back as an error instead of leaving the page waiting.
importScripts('../vendor/occt/occt-import-js.js');

let occt = null;

const fail = (e) => postMessage({ ok: false, error: String((e && e.message) || e) });
self.addEventListener('unhandledrejection', (ev) => fail(ev.reason));

onmessage = async (ev) => {
  try {
    occt ??= await occtimportjs({ locateFile: (path) => `../vendor/occt/${path}` });
    const result = occt.ReadFile(ev.data.format, ev.data.buffer, ev.data.params);
    postMessage({ ok: true, result });
  } catch (e) {
    fail(e);
  }
};
