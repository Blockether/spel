## GET http://localhost:35509/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 24 Oct 2026 08:08:32 GMT
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
curl 'http://localhost:35509/echo?id=1'
```
