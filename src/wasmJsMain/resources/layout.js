const rowEl = document.getElementById("input-row");
const codeEl = document.getElementById("code");
const inputEl = document.getElementById("input");
const splitterEl = document.getElementById("splitter");

const MIN_PANE_WIDTH = 160;

let syncing = false;
let drag = null;

function sync(sourceEl) {
    if (syncing) return;
    syncing = true;
    const targetHeight = sourceEl.getBoundingClientRect().height;
    codeEl.style.height = `${targetHeight}px`;
    inputEl.style.height = `${targetHeight}px`;
    syncing = false;
}

(() => {
    const o = new ResizeObserver(entries => {
        if (entries.length === 0) return;
        sync(entries[0].target);
    });
    o.observe(codeEl);
    o.observe(inputEl);
})();

function setWidth(widthPx) {
    const rowWidth = rowEl.getBoundingClientRect().width;
    const rectWidth = splitterEl.getBoundingClientRect().width;
    const styles = getComputedStyle(splitterEl);
    const marginLeft = parseFloat(styles.marginLeft) || 0;
    const marginRight = parseFloat(styles.marginRight) || 0;
    const splitterWidth = rectWidth + marginLeft + marginRight;
    const maxInput = Math.max(MIN_PANE_WIDTH, rowWidth - splitterWidth - MIN_PANE_WIDTH);
    widthPx = Math.min(maxInput, Math.max(MIN_PANE_WIDTH, widthPx));
    inputEl.style.flexBasis = `${widthPx}px`;
}

function start(clientX) {
    drag = {
        startX: clientX,
        startInputWidth: inputEl.getBoundingClientRect().width
    };
    document.body.style.userSelect = "none";
    document.body.style.cursor = "col-resize";
}

function stop() {
    if (!drag) return;
    drag = null;
    document.body.style.userSelect = "";
    document.body.style.cursor = "";
}

splitterEl.addEventListener("pointerdown", event => {
    if (event.button !== 0) return;
    event.preventDefault();
    start(event.clientX);
    splitterEl.setPointerCapture?.(event.pointerId);
});

window.addEventListener("pointermove", event => {
    if (!drag) return;
    const deltaX = event.clientX - drag.startX;
    setWidth(drag.startInputWidth - deltaX);
});

window.addEventListener("pointerup", stop);
window.addEventListener("pointercancel", stop);

splitterEl.addEventListener("keydown", event => {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight") return;
    event.preventDefault();
    const direction = event.key === "ArrowLeft" ? 1 : -1;
    const current = inputEl.getBoundingClientRect().width;
    setWidth(current + direction * 16);
});

window.addEventListener("resize", () => {
    const current = inputEl.getBoundingClientRect().width;
    setWidth(current);
});

setWidth(inputEl.getBoundingClientRect().width);
