## GET http://localhost:42247/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Thu, 19 Nov 2026 13:01:45 GMT
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
curl 'http://localhost:42247/echo?id=1'
```
