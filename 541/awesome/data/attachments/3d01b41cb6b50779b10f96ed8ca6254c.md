## GET http://localhost:40741/echo?id=1 → 200 OK

### Request Headers
```
GET http://localhost:40741/echo?id=1
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Thu, 09 Apr 2026 19:57:13 GMT
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
curl 'http://localhost:40741/echo?id=1'
```
