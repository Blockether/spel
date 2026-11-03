## GET http://localhost:37963/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 03 Nov 2026 17:51:39 GMT
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
curl 'http://localhost:37963/echo?id=1'
```
