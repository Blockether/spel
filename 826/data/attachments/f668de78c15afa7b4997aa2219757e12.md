## GET http://localhost:35399/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Wed, 04 Nov 2026 12:09:33 GMT
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
curl 'http://localhost:35399/echo?id=1'
```
