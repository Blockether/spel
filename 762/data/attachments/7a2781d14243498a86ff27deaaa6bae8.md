## GET http://localhost:42431/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Thu, 24 Sep 2026 22:45:27 GMT
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
curl 'http://localhost:42431/echo?id=1'
```
