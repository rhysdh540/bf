const instructions = "><+-.,[]";

const code = new URLSearchParams(window.location.search).get('code');
if (code) {
    const binary = atob(code);

    let bits = "";
    for (let i = 0; i < binary.length; i++) {
        bits += binary.charCodeAt(i).toString(2).padStart(8, "0");
    }

    let output = "";
    for (let i = 0; i + 2 < bits.length; i += 3) {
        const value = parseInt(bits.slice(i, i + 3), 2);
        output += instructions[value];
    }

    document.getElementById('code').value = output;
}

document.getElementById("export").addEventListener("click", () => {
    const code = document.getElementById("code").value;
    let bits = "";
    for (let i = 0; i < code.length; i++) {
        const value = instructions.indexOf(code[i]);
        if (value !== -1) {
            bits += value.toString(2).padStart(3, "0");
        }
    }

    bits += "0".repeat((8 - bits.length % 8) % 8);

    let binary = "";
    for (let i = 0; i + 7 < bits.length; i += 8) {
        const byte = bits.slice(i, i + 8);
        binary += String.fromCharCode(parseInt(byte, 2));
    }

    const url = new URL(window.location.href);
    url.searchParams.set("code", btoa(binary));
    window.history.replaceState({}, document.title, url.pathname + url.search + url.hash);
});