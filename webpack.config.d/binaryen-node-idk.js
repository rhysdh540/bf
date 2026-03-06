// binaryen's package references Node's "module" builtin;
// disable that builtin in browser bundles
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
    module: false,
});