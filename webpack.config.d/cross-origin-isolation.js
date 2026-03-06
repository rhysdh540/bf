// need this to enable shared array buffers in dev
config.devServer = config.devServer || {};
config.devServer.headers = Object.assign({}, config.devServer.headers, {
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Embedder-Policy": "require-corp",
});

// binaryen's package references Node's "module" builtin;
// disable that builtin in browser bundles.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, {
  module: false,
});
