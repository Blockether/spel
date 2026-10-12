// Cache missing or invalid maps as empty maps. Preserve the original positions.
(function (support) {
  var emptyMap = {version: 3, sources: [], names: [], mappings: ''};
  var reading = false;
  support.install({
    environment: 'browser',
    retrieveSourceMap: function (source) {
      if (reading) return null;
      reading = true;
      try {
        var found = support.retrieveSourceMap(source);
        if (!found) return {url: source, map: emptyMap};
        var map = typeof found.map === 'string'
          ? JSON.parse(found.map.replace(/^\)\]\}'[^\n]*\n/, ''))
          : found.map;
        return {url: found.url, map: map && map.version === 3 ? map : emptyMap};
      } catch (error) {
        return {url: source, map: emptyMap};
      } finally {
        reading = false;
      }
    }
  });
})(sourceMapSupport);
