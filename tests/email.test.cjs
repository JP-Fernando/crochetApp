const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const { setImmediate: nextTurn } = require('node:timers/promises');

// Exercise the actual email code in index.html without loading the PDF.js bundle.
const html = readFileSync(require('node:path').join(__dirname, '../index.html'), 'utf8');
const source = html.slice(html.indexOf('const LAST_EMAIL_KEY'), html.indexOf('/* ═', html.indexOf('const LAST_EMAIL_KEY')));

function app(bridge, project = { name: 'Pattern', pdf: new Uint8Array([37, 80, 68, 70]), comment: 'My notes' }) {
  const elements = new Map();
  const document = {
    getElementById(id) {
      if (!elements.has(id)) {
        const classes = new Set();
        elements.set(id, {
          value: '', textContent: '', disabled: false,
          classList: { add: c => classes.add(c), remove: c => classes.delete(c), contains: c => classes.has(c) },
          focus() {}, addEventListener() {},
        });
      }
      return elements.get(id);
    },
  };
  const context = vm.createContext({
    window: { AndroidEmail: bridge }, document,
    Uint8Array, ArrayBuffer, btoa, setTimeout,
    DB: { get: async () => project },
    localStorage: { getItem: () => '', setItem() {} },
    console: { error() {} },
  });
  vm.runInContext(source, context);
  return { context, document, run: code => vm.runInContext(code, context) };
}

test('large binary PDFs survive chunking exactly, including the last partial chunk', async () => {
  const chunks = [];
  let metadata;
  let appState;
  const bridge = {
    begin: name => (assert.equal(name, 'Pattern.pdf'), true),
    append: chunk => (chunks.push(Buffer.from(chunk, 'base64')), true),
    send: (...args) => {
      metadata = args;
      setImmediate(() => appState.context.window.onAndroidEmailResult('opened'));
      return true;
    },
    cancel() { assert.fail('successful transfers must not be cancelled'); },
  };
  appState = app(bridge);
  const bytes = Uint8Array.from({ length: 384 * 1024 * 10 + 17 }, (_, i) => i % 256);
  appState.context.bytes = bytes;
  await appState.run('sendViaAndroid(bytes, "Pattern.pdf", "person@example.com", "Subject", "Notes")');
  assert.deepEqual(Buffer.concat(chunks), Buffer.from(bytes));
  assert.equal(chunks.length, 11);
  assert.deepEqual(metadata, ['person@example.com', 'Subject', 'Notes']);
  assert.equal(appState.context.window.onAndroidEmailResult, undefined);
});

test('dialog waits for Android; launch failure remains visible and can be retried', async () => {
  let sends = 0, cancels = 0;
  const state = app({ begin: () => true, append: () => true, send: () => (++sends, true), cancel: () => cancels++ });
  state.run('openEmailDialog(1, "Pattern")');
  state.document.getElementById('email-input').value = 'person@example.com';
  const first = state.run('sendProjectByEmail()');
  while (!sends) await nextTurn();
  assert.equal(state.document.getElementById('email-send').disabled, true);
  assert.equal(state.document.getElementById('email-modal').classList.contains('open'), true);
  state.context.window.onAndroidEmailResult('error');
  await first;
  assert.match(state.document.getElementById('email-error').textContent, /No app could open/);
  assert.equal(state.document.getElementById('email-send').disabled, false);
  assert.equal(state.document.getElementById('email-modal').classList.contains('open'), true);
  assert.equal(cancels, 1);

  const second = state.run('sendProjectByEmail()');
  while (sends < 2) await nextTurn();
  state.context.window.onAndroidEmailResult('opened');
  await second;
  assert.equal(state.document.getElementById('email-modal').classList.contains('open'), false);
});

for (const stage of ['begin', 'append', 'send']) {
  test(`native ${stage} failure discards the partial transfer`, async () => {
    let cancelled = false;
    const bridge = { begin: () => true, append: () => true, send: () => true, cancel: () => { cancelled = true; } };
    bridge[stage] = () => false;
    const state = app(bridge);
    await assert.rejects(state.run('sendViaAndroid(new Uint8Array([1, 2, 3]), "a.pdf", "a@b.com", "s", "b")'));
    assert.equal(cancelled, true);
    assert.equal(state.context.window.onAndroidEmailResult, undefined);
  });
}

test('empty PDFs never open an email app', async () => {
  const state = app({ begin() { assert.fail('empty PDF'); }, cancel() {} });
  await assert.rejects(state.run('sendViaAndroid(new Uint8Array(), "a.pdf", "a@b.com", "s", "b")'), /empty/);
});

test('recipient validation prevents transfers and duplicate taps do not send twice', async () => {
  let begins = 0, sends = 0;
  const state = app({ begin: () => (++begins, true), append: () => true, send: () => (++sends, true), cancel() {} });
  state.run('openEmailDialog(1, "Pattern")');
  state.document.getElementById('email-input').value = 'invalid';
  await state.run('sendProjectByEmail()');
  assert.equal(begins, 0);
  assert.match(state.document.getElementById('email-error').textContent, /valid email/);
  state.document.getElementById('email-input').value = 'person@example.com';
  const sending = state.run('sendProjectByEmail()');
  await state.run('sendProjectByEmail()');
  while (!sends) await nextTurn();
  assert.equal(begins, 1);
  state.context.window.onAndroidEmailResult('cancelled');
  await sending;
  assert.equal(state.document.getElementById('email-send').disabled, false);
  assert.equal(state.document.getElementById('email-modal').classList.contains('open'), true);
  assert.equal(state.document.getElementById('email-input').value, 'person@example.com');
  assert.equal(state.document.getElementById('email-error').textContent, '');
  assert.equal(state.context.window.onAndroidEmailResult, undefined);

  const retry = state.run('sendProjectByEmail()');
  while (sends < 2) await nextTurn();
  state.context.window.onAndroidEmailResult('opened');
  await retry;
  assert.equal(state.document.getElementById('email-modal').classList.contains('open'), false);
});

test('missing project reports an error and leaves the dialog usable', async () => {
  const state = app({}, null);
  state.run('openEmailDialog(1, "Pattern")');
  state.document.getElementById('email-input').value = 'person@example.com';
  await state.run('sendProjectByEmail()');
  assert.match(state.document.getElementById('email-error').textContent, /Project not found/);
  assert.equal(state.document.getElementById('email-send').disabled, false);
});

test('file names and typed array views preserve names and exact byte ranges', () => {
  const state = app({});
  assert.equal(state.run('pdfFileName("Pattern.PDF")'), 'Pattern.pdf');
  assert.equal(state.run('pdfFileName("folder/pattern")'), 'folder_pattern.pdf');
  assert.equal(state.run('pdfFileName("")'), 'Pattern.pdf');
  const bytes = new Uint8Array([0, 37, 80, 68, 70, 0]);
  state.context.view = new DataView(bytes.buffer, 1, 4);
  assert.deepEqual(Array.from(state.run('toUint8(view)')), [37, 80, 68, 70]);
});
