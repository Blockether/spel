## GET http://localhost:44369/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Wed, 11 Nov 2026 22:40:10 GMT
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
curl 'http://localhost:44369/echo?id=1'
```
