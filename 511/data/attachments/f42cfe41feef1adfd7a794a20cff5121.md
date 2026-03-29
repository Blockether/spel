## GET http://localhost:46879/echo?id=1 → 200 OK

### Request Headers
```
GET http://localhost:46879/echo?id=1
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Sun, 29 Mar 2026 20:47:29 GMT
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
curl 'http://localhost:46879/echo?id=1'
```
