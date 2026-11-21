## GET http://localhost:41771/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Sat, 21 Nov 2026 17:55:58 GMT
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
curl 'http://localhost:41771/echo?id=1'
```
