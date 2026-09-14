## GET http://localhost:37925/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 14 Sep 2026 21:31:30 GMT
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
curl 'http://localhost:37925/echo?id=1'
```
