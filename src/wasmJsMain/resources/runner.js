const codeEl = document.getElementById('code');
const optimiseEl = document.getElementById('optimise');
const outputEl = document.getElementById('output');
const runBtn = document.getElementById('run');
const timeEl = document.getElementById('time');
const instantiateUrl = new URL("./bf.uninstantiated.mjs", import.meta.url).href;

runBtn.disabled = true;
timeEl.textContent = "Loading runtime...";

const workerUrl = new URL("./worker.mjs", import.meta.url).href;

const worker = new Worker(workerUrl, {type: "module"});
let workerReady = false;
let running = false;
let currentRequestId = 0;
let currentRunUsesSab = false;
let currentOutputData = null;
let currentOutputCtl = null;
let drainRafId = 0;
let pendingDoneMessage = null;
let outputDecoder = new TextDecoder();

const CTRL_WRITE = 0;
const CTRL_READ = 1;
const CTRL_DONE = 2;
const OUTPUT_BUFFER_SIZE = 1024 * 1024;
const supportsSab = globalThis.crossOriginIsolated === true && typeof SharedArrayBuffer === "function";

function formatTime(ms) {
    if (ms < 1000) {
        return `${ms.toFixed(2)} ms`;
    } else {
        return `${(ms / 1000).toFixed(2)} s`;
    }
}

function finishRun(doneMsg) {
    if (doneMsg == null) return;
    running = false;
    runBtn.disabled = false;
    outputEl.value += outputDecoder.decode();
    const compileLabel = doneMsg.compileMs > 0.0 ? formatTime(doneMsg.compileMs) : "cached";
    timeEl.innerHTML = `<span title="compile: ${compileLabel}, execute: ${formatTime(doneMsg.runMs)}">done in ${formatTime(doneMsg.workerTotalMs)}</span>`;
}

function drainBufAndMaybeFinish() {
    drainRafId = 0;

    if (!currentRunUsesSab || currentOutputData == null || currentOutputCtl == null) {
        if (pendingDoneMessage != null) {
            const msg = pendingDoneMessage;
            pendingDoneMessage = null;
            finishRun(msg);
        }
        return;
    }

    const write = Atomics.load(currentOutputCtl, CTRL_WRITE);
    let read = Atomics.load(currentOutputCtl, CTRL_READ);
    let text = "";
    while (read < write) {
        const index = read % OUTPUT_BUFFER_SIZE;
        const chunk = Math.min(write - read, OUTPUT_BUFFER_SIZE - index);
        const decodeChunk = new Uint8Array(chunk);
        decodeChunk.set(currentOutputData.subarray(index, index + chunk));
        text += outputDecoder.decode(decodeChunk, {stream: true});
        read += chunk;
    }
    if (text.length > 0) {
        outputEl.value += text;
        outputEl.scrollTop = outputEl.scrollHeight;
    }

    Atomics.store(currentOutputCtl, CTRL_READ, read);
    Atomics.notify(currentOutputCtl, CTRL_READ, 1);

    if (pendingDoneMessage != null) {
        const done = Atomics.load(currentOutputCtl, CTRL_DONE) === 1;
        const drained = Atomics.load(currentOutputCtl, CTRL_READ) === Atomics.load(currentOutputCtl, CTRL_WRITE);
        if (done && drained) {
            const msg = pendingDoneMessage;
            pendingDoneMessage = null;
            finishRun(msg);
            return;
        }
    }

    if (running || pendingDoneMessage != null) {
        drainRafId = requestAnimationFrame(drainBufAndMaybeFinish);
    }
}

worker.onmessage = (event) => {
    const msg = event.data;
    if (msg.id !== undefined && msg.id !== currentRequestId && msg.type !== "ready") {
        return;
    }

    if (msg.type === "ready") {
        workerReady = true;
        runBtn.disabled = false;
        timeEl.textContent = "Runtime ready";
        runBtn.removeAttribute("disabled");
        return;
    }

    if (msg.type === "out") {
        outputEl.value += String.fromCharCode(msg.byte);
        outputEl.scrollTop = outputEl.scrollHeight;
        return;
    }

    if (msg.type === "done") {
        if (currentRunUsesSab) {
            pendingDoneMessage = msg;
            if (drainRafId === 0) {
                drainRafId = requestAnimationFrame(drainBufAndMaybeFinish);
            }
        } else {
            finishRun(msg);
        }
        return;
    }

    if (msg.type === "error") {
        running = false;
        runBtn.disabled = !workerReady;
        pendingDoneMessage = null;
        if (outputEl.value.length !== 0 && !outputEl.value.endsWith('\n')) {
            outputEl.value += "\n";
        }
        outputEl.value += `[worker error] ${msg.message}\n`;
        timeEl.textContent = "Run failed";
    }
};

runBtn.addEventListener('click', () => {
    if (!workerReady || running) {
        return;
    }

    outputEl.value = "";
    outputDecoder = new TextDecoder();
    pendingDoneMessage = null;
    running = true;
    runBtn.disabled = true;
    currentRequestId += 1;
    currentRunUsesSab = supportsSab;
    if (currentRunUsesSab) {
        currentOutputData = new Uint8Array(new SharedArrayBuffer(OUTPUT_BUFFER_SIZE));
        currentOutputCtl = new Int32Array(new SharedArrayBuffer(Int32Array.BYTES_PER_ELEMENT * 3));
        Atomics.store(currentOutputCtl, CTRL_WRITE, 0);
        Atomics.store(currentOutputCtl, CTRL_READ, 0);
        Atomics.store(currentOutputCtl, CTRL_DONE, 0);
        if (drainRafId !== 0) {
            cancelAnimationFrame(drainRafId);
            drainRafId = 0;
        }
        drainRafId = requestAnimationFrame(drainBufAndMaybeFinish);
    } else {
        currentOutputData = null;
        currentOutputCtl = null;
    }
    timeEl.textContent = currentRunUsesSab ? "Running (shared buffer)..." : "Running (fallback mode)...";

    worker.postMessage({
        type: "run",
        id: currentRequestId,
        instantiateUrl,
        code: codeEl.value,
        optimise: optimiseEl.checked,
        outputMode: currentRunUsesSab ? "sab" : "message",
        outputDataBuffer: currentRunUsesSab ? currentOutputData.buffer : null,
        outputCtlBuffer: currentRunUsesSab ? currentOutputCtl.buffer : null
    });
});

currentRequestId += 1;
worker.postMessage({
    type: "init",
    id: currentRequestId,
    instantiateUrl
});

window.addEventListener("beforeunload", () => {
    if (drainRafId !== 0) {
        cancelAnimationFrame(drainRafId);
    }
    worker.terminate();
});
