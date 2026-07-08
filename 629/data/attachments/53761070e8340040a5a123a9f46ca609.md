## GET http://localhost:41811/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Wed, 08 Jul 2026 16:44:35 GMT
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
curl 'http://localhost:41811/echo?id=1'
```
