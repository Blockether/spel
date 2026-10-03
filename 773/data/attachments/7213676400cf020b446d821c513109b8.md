## GET http://localhost:44853/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 03 Oct 2026 07:24:49 GMT
```

### Response Body
```json
{
  "method": "GET",
  "path": "/echo",
  "query": "id=1"
}
```

### cURL
```bash
curl 'http://localhost:44853/echo?id=1'
```
