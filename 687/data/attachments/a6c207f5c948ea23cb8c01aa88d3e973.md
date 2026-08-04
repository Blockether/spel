## GET http://localhost:39835/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 04 Aug 2026 22:12:03 GMT
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
curl 'http://localhost:39835/echo?id=1'
```
