## GET http://localhost:39733/echo?id=1 → 200 OK

### Request Headers
```
GET http://localhost:39733/echo?id=1
```

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 10 Apr 2026 08:22:07 GMT
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
curl 'http://localhost:39733/echo?id=1'
```
