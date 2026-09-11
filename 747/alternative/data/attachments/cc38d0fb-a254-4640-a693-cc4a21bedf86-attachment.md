## GET http://localhost:44341/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 11 Sep 2026 15:01:06 GMT
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
