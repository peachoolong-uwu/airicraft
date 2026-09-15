// Filler priority is assigned by the host manifest, not by generated code.
function* main(os, config) {
  for (;;) {
    const result = yield os.action('fish_once', { site: config.site });
    if (!result.ok) yield os.sleep(5000);
  }
}
