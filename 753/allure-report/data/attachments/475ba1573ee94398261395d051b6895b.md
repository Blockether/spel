## GET http://localhost:41219/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Tue, 15 Sep 2026 11:16:50 GMT
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
curl 'http://localhost:41219/echo?id=1'
```
