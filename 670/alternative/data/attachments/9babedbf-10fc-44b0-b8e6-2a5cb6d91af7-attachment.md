## GET http://localhost:45729/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 28 Jul 2026 15:14:02 GMT
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
curl 'http://localhost:45729/echo?id=1'
```
