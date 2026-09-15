// One running instance per configured plot. Growth never retains the player.
function* main(os, config) {
  for (;;) {
    const plot = yield os.observe(config.plot);
    if (!plot.known || !plot.ready) {
      yield os.wait(config.plot);
      continue;
    }
    const result = yield os.action('tend_crops', { plot: config.plot });
    if (!result.ok) yield os.sleep(5000);
  }
}
