// deploy to subpath, since we do the same on github pages
config.devServer.static = config.devServer.static || [];
for (const s of config.devServer.static) {
    s.publicPath = "/bf";
}

config.devServer.open = ["/bf"];