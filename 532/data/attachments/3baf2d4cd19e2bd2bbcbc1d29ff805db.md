## GET http://localhost:35143/echo?id=1 → 200 OK

### Request Headers
```
GET http://localhost:35143/echo?id=1
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 06 Apr 2026 04:23:17 GMT
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
curl 'http://localhost:35143/echo?id=1'
```
