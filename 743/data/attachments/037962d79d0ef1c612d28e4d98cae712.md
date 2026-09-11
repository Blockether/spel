## GET http://localhost:35531/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Fri, 11 Sep 2026 08:05:07 GMT
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
curl 'http://localhost:35531/echo?id=1'
```
