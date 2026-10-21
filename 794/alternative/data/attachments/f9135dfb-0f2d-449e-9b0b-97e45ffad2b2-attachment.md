## GET http://localhost:33261/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 20 Oct 2026 23:37:42 GMT
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
curl 'http://localhost:33261/echo?id=1'
```
