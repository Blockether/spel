## GET http://localhost:41095/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 12 Apr 2026 08:34:06 GMT
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
curl 'http://localhost:41095/echo?id=1'
```
