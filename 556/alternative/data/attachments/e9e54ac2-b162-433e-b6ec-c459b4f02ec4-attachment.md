## GET http://localhost:44341/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 11 Apr 2026 12:15:08 GMT
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
curl 'http://localhost:44341/echo?id=1'
```
