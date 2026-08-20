## GET http://localhost:43313/test-page → 200 OK

### Timing
Request started: 2026-08-20T12:41:27.369Z

### Request Headers
```
accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7
accept-encoding: gzip, deflate, br, zstd
connection: keep-alive
host: localhost:43313
sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"
sec-ch-ua-mobile: ?0
sec-ch-ua-platform: "Linux"
sec-fetch-dest: document
sec-fetch-mode: navigate
sec-fetch-site: none
sec-fetch-user: ?1
upgrade-insecure-requests: 1
user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36
```

### Response Headers
```
content-length: 1658
content-type: text/html; charset=UTF-8
date: Thu, 20 Aug 2026 12:41:27 GMT
```

### Response Body
```html
<!DOCTYPE html>
<html><head><title>Test Page</title></head>
<body>
  <h1 id="heading">Test Heading</h1>
  <p id="description">Test description paragraph.</p>
  <div id="content">
    <a id="link" href="/second-page">Go to Second Page</a>
    <form id="test-form">
      <label for="text-input">Name</label>
      <input type="text" id="text-input" placeholder="Enter text" aria-label="Name" />
      <input type="text" id="prefilled" value="initial value" />
      <input type="password" id="password-input" placeholder="Password" />
      <input type="checkbox" id="checkbox" />
      <input type="checkbox" id="checked-box" checked />
      <select id="dropdown">
        <option value="a">Option A</option>
        <option value="b">Option B</option>
        <option value="c">Option C</option>
      </select>
      <textarea id="textarea"></textarea>
      <input type="file" id="file-input" />
      <button id="submit-btn" type="button" data-testid="submit">Submit</button>
    </form>
    <button id="hidden-btn" style="display:none">Hidden</button>
    <button id="disabled-btn" disabled>Disabled</button>
    <ul role="listbox"><li role="option" id="aria-disabled-opt" aria-disabled="true">Disabled Option</li><li role="option" id="aria-enabled-opt">Enabled Option</li></ul>
    <div id="hover-target" title="Hover tooltip">Hover Me</div>
    <div id="scroll-anchor" style="margin-top:2000px">Scroll Target</div>
    <img id="logo" alt="Test Logo" src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" />
  </div>
  <script>
    window.testReady = true;
    console.log('test-page-loaded');
  </script>
</body></html>
```

### cURL
```bash
curl 'http://localhost:43313/test-page' \
  -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
  -H 'accept-encoding: gzip, deflate, br, zstd' \
  -H 'connection: keep-alive' \
  -H 'host: localhost:43313' \
  -H 'sec-ch-ua: "HeadlessChrome";v="149", "Chromium";v="149", "Not)A;Brand";v="24"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: document' \
  -H 'sec-fetch-mode: navigate' \
  -H 'sec-fetch-site: none' \
  -H 'sec-fetch-user: ?1' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) HeadlessChrome/149.0.7827.55 Safari/537.36'
```
