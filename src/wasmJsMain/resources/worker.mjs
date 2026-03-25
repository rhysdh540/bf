let runtimePromise = null;
let wasmExports = null;
let compiledHandle = 0;
let compiledKey = "";
let activeRunId = 0;
let outputMode = "message";
let outputData = null;
let outputCtl = null;
let outputCapacity = 0;

let input = null;
let inputPos = 0;

const CTRL_WRITE = 0;
const CTRL_READ = 1;
const CTRL_DONE = 2;

function errorMessage(err) {
    if (err && typeof err.message === "string") return err.message;
    return String(err);
}

function configureOutput(mode, dataBuffer, ctlBuffer) {
    outputMode = mode === "sab" ? "sab" : "message";
    if (outputMode === "sab") {
        outputData = new Uint8Array(dataBuffer);
        outputCtl = new Int32Array(ctlBuffer);
        outputCapacity = outputData.length;
        Atomics.store(outputCtl, CTRL_WRITE, 0);
        Atomics.store(outputCtl, CTRL_READ, 0);
        Atomics.store(outputCtl, CTRL_DONE, 0);
    } else {
        outputData = null;
        outputCtl = null;
        outputCapacity = 0;
    }
}

function writeByte(v) {
    const byte = v & 0xff;
    if (outputMode === "message") {
        self.postMessage({type: "out", id: activeRunId, byte});
        return;
    }

    while (true) {
        const write = Atomics.load(outputCtl, CTRL_WRITE);
        const read = Atomics.load(outputCtl, CTRL_READ);
        if (write - read < outputCapacity) {
            outputData[write % outputCapacity] = byte;
            Atomics.store(outputCtl, CTRL_WRITE, write + 1);
            return;
        }
        Atomics.wait(outputCtl, CTRL_READ, read, 10);
    }
}

async function initRuntime(instantiateUrl) {
    if (runtimePromise) return runtimePromise;
    runtimePromise = (async () => {
        const binaryenNs = await import(("https://cdn.jsdelivr.net/npm/binaryen@125.0.0/index.js"));
        const {instantiate} = await import(instantiateUrl);
        const {exports} = await instantiate({
            bf: {
                read: () => {
                    if (!input || inputPos >= input.length) return -1;
                    return input.charCodeAt(inputPos++) & 0xff;
                },
                write: (v) => writeByte(v | 0),
                flush: () => {}
            },
            binaryen: binaryenNs
        });
        if (typeof exports.compileProgram !== "function" || typeof exports.executeProgram !== "function") {
            throw new Error("Missing compileProgram/executeProgram exports");
        }
        wasmExports = exports;
        return true;
    })();
    return runtimePromise;
}

self.onmessage = async (event) => {
    const msg = event.data;
    try {
        if (msg.type === "init") {
            await initRuntime(msg.instantiateUrl);
            self.postMessage({type: "ready", id: msg.id});
            return;
        }

        if (msg.type === "reset") {
            if (wasmExports && typeof wasmExports.clearProgramCache === "function") {
                wasmExports.clearProgramCache();
            }
            compiledHandle = 0;
            compiledKey = "";
            self.postMessage({type: "reset-done", id: msg.id});
            return;
        }

        if (msg.type !== "run") {
            self.postMessage({type: "error", id: msg.id, message: "Unknown worker message: " + msg.type});
            return;
        }

        await initRuntime(msg.instantiateUrl);
        const workerStart = performance.now();
        input = msg.input ? String(msg.input) : "";
        inputPos = 0;

        const key = msg.code;
        activeRunId = msg.id;
        configureOutput(msg.outputMode, msg.outputDataBuffer, msg.outputCtlBuffer);

        let compileMs = 0;
        if (key !== compiledKey) {
            const compileStart = performance.now();
            compiledHandle = wasmExports.compileProgram(msg.code);
            compileMs = performance.now() - compileStart;
            compiledKey = key;
        }

        const runStart = performance.now();
        wasmExports.executeProgram(compiledHandle);
        const runMs = performance.now() - runStart;
        if (outputMode === "sab") {
            Atomics.store(outputCtl, CTRL_DONE, 1);
            Atomics.notify(outputCtl, CTRL_READ, 1);
        }
        const workerTotalMs = performance.now() - workerStart;
        self.postMessage({type: "done", id: msg.id, compileMs, runMs, workerTotalMs});
    } catch (err) {
        self.postMessage({type: "error", id: msg.id, message: errorMessage(err)});
        console.error("Worker error:", err);
    }
};
