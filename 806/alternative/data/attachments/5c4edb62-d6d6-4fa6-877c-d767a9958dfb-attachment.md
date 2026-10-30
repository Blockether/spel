## GET http://localhost:34069/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 30 Oct 2026 11:14:35 GMT
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
curl 'http://localhost:34069/echo?id=1'
```
