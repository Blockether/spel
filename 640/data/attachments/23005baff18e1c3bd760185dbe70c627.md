## GET http://localhost:41621/echo?id=1 → 200 OK

### Response Headers
```
content-length: 46
content-type: application/json
date: Mon, 20 Jul 2026 16:58:53 GMT
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
curl 'http://localhost:41621/echo?id=1'
```
